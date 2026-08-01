package com.docstruct.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import com.docstruct.domain.CollectionEntity;
import com.docstruct.domain.schema.SchemaColumn;
import com.docstruct.dto.QueryResponse.QueryResultDto;
import com.docstruct.exception.QueryException;
import com.docstruct.llm.LlmClient;
import com.docstruct.llm.PromptTemplates;
import com.docstruct.repository.DynamicTableRepository;
import com.docstruct.util.InternalColumns;
import com.docstruct.util.SqlNameSanitizer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.TableFunction;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.util.TablesNamesFinder;

/**
 * Natural-language querying: translates the user's question into a
 * PostgreSQL SELECT via the LLM, validates it, executes it, and
 * summarizes the results.
 */
@Service
public class QueryService {

    private static final Logger log = LoggerFactory.getLogger(QueryService.class);

    // Generous budget: the model's internal reasoning tokens count against this limit
    private static final int SQL_MAX_TOKENS = 8192;
    private static final int SUMMARY_MAX_TOKENS = 1024;
    private static final Pattern FORBIDDEN_KEYWORDS = Pattern.compile(
            "\\b(DROP|DELETE|INSERT|UPDATE|ALTER|CREATE|TRUNCATE|GRANT|REVOKE|EXEC|EXECUTE|COPY|VACUUM)\\b",
            Pattern.CASE_INSENSITIVE);
    /** PostgreSQL system schemas and catalog tables. */
    private static final Pattern SYSTEM_CATALOG = Pattern.compile(
            "\\b(pg_catalog|information_schema|pg_[a-z_]+)\\b", Pattern.CASE_INSENSITIVE);
    /** Bounds the parse so a pathological statement cannot pin a request thread. */
    private static final long PARSE_TIMEOUT_MS = 2_000;
    private static final int SUMMARY_ROW_SAMPLE = 20;

    private final CollectionService collectionService;
    private final DynamicTableRepository dynamicTableRepository;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public QueryService(CollectionService collectionService,
                        DynamicTableRepository dynamicTableRepository,
                        LlmClient llmClient,
                        ObjectMapper objectMapper) {
        this.collectionService = collectionService;
        this.dynamicTableRepository = dynamicTableRepository;
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    public QueryResultDto query(String collectionId, String query) {
        CollectionEntity collection = collectionService.getOrThrow(collectionId);

        String tablesSchema = describeTables(
                collection.getSchema().columns(),
                DynamicTableRepository.dataTableName(collectionId),
                collectionId);

        GeneratedSql generated = naturalLanguageToSql(
                query.trim(), tablesSchema, allowedTables(collectionId, collection.getSchema().columns()));

        DynamicTableRepository.QueryResultRows result;
        try {
            result = dynamicTableRepository.executeSelect(generated.sql());
        } catch (DataAccessException e) {
            String message = e.getMostSpecificCause() != null
                    ? e.getMostSpecificCause().getMessage() : e.getMessage();
            throw new QueryException("SQL execution failed: " + message, "Generated SQL: " + generated.sql());
        }

        List<Map<String, Object>> filteredRows = InternalColumns.stripAll(result.rows());
        String summary = summarizeResults(query, generated.sql(), filteredRows);

        return new QueryResultDto(
                result.columns(),
                filteredRows,
                filteredRows.size(),
                generated.sql(),
                generated.explanation(),
                summary);
    }

    // ---- NL to SQL ----

    private record GeneratedSql(String sql, String explanation) {
    }

    private GeneratedSql naturalLanguageToSql(String query, String tablesSchema, Set<String> allowedTables) {
        log.info("Translating query to SQL: {}", query);

        JsonNode parsed = llmClient.callJson(
                PromptTemplates.queryToSql(query, tablesSchema), null, SQL_MAX_TOKENS);

        String sql = parsed.path("sql").asText(null);
        if (sql == null || sql.isBlank()) {
            throw new QueryException("AI failed to generate a valid SQL query");
        }

        validateSql(sql, allowedTables);

        log.info("Query translated to SQL: {}", sql);
        return new GeneratedSql(sql, parsed.path("explanation").asText(""));
    }

    /**
     * Defense in depth: only a single read-only SELECT statement is ever executed.
     * The textual checks here are a cheap first pass — they reject the obvious
     * cases before the parser runs, and the chaining check is load-bearing because
     * JSQLParser happily parses the first statement of {@code SELECT 1; DROP ...}
     * and discards the rest. The whitelist itself is decided on the parsed AST.
     */
    private void validateSql(String sql, Set<String> allowedTables) {
        String trimmed = sql.trim();
        String upper = trimmed.toUpperCase(Locale.ROOT);
        if (!upper.startsWith("SELECT") && !upper.startsWith("WITH")) {
            throw new QueryException("Only SELECT queries are allowed", "Generated: " + sql);
        }
        var matcher = FORBIDDEN_KEYWORDS.matcher(trimmed);
        if (matcher.find()) {
            throw new QueryException(
                    "Query contains forbidden operation: " + matcher.group(1).toUpperCase(Locale.ROOT),
                    "Only read-only SELECT queries are supported");
        }
        // Reject statement chaining (a semicolon followed by anything else)
        String withoutTrailing = trimmed.endsWith(";") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        if (withoutTrailing.contains(";")) {
            throw new QueryException("Multiple SQL statements are not allowed", "Generated: " + sql);
        }
        validateTableReferences(trimmed, allowedTables);
    }

    /**
     * Whitelist check: the query may only touch this collection's own data tables.
     * System catalogs are rejected textually first, then the statement is parsed and
     * every table node in the tree is checked — so a foreign table hidden in a CTE,
     * a subquery, a comma join or the second arm of a UNION is caught the same way
     * one sitting in the top-level FROM is. Anything that fails to parse is rejected
     * rather than executed unvalidated.
     */
    private void validateTableReferences(String sql, Set<String> allowedTables) {
        Matcher catalog = SYSTEM_CATALOG.matcher(sql);
        if (catalog.find()) {
            throw new QueryException(
                    "Query references system tables: " + catalog.group(1),
                    "Only this collection's data tables can be queried");
        }

        Statement statement;
        try {
            statement = CCJSqlParserUtil.parse(sql, parser -> parser.withTimeOut(PARSE_TIMEOUT_MS));
        } catch (Exception e) {
            log.warn("Rejecting generated SQL that failed to parse: {}", e.getMessage());
            throw new QueryException(
                    "Generated SQL could not be parsed for validation",
                    "Generated: " + sql);
        }
        if (!(statement instanceof Select)) {
            throw new QueryException("Only SELECT queries are allowed", "Generated: " + sql);
        }
        new TableWhitelistVisitor(allowedTables).enforce(statement);
    }

    /** Quoted and unquoted identifiers both fold to the lower-case names we generate. */
    private static String normalizeIdentifier(String name) {
        return name.replace("\"", "").toLowerCase(Locale.ROOT);
    }

    /**
     * Visits every table node in a parsed SELECT and holds it against the whitelist.
     *
     * <p>CTE names are tracked as they come into scope, because {@code FROM my_cte}
     * is a reference to a query, not to a table. Scope is tracked rather than
     * collected up front so that a CTE cannot launder a reference to a real table:
     * a CTE named after a physical table only covers references that PostgreSQL
     * would also resolve to it — ones inside the statement that declares it, and
     * after its own declaration.
     */
    private static final class TableWhitelistVisitor extends TablesNamesFinder<Void> {

        private final Set<String> allowedTables;
        private final Set<String> inScope;
        private String foreignTable;
        private boolean sawTableFunction;
        private boolean sawAllowedTable;

        TableWhitelistVisitor(Set<String> allowedTables) {
            this.allowedTables = allowedTables;
            this.inScope = new HashSet<>(allowedTables);
        }

        void enforce(Statement statement) {
            getTables(statement);
            if (foreignTable != null) {
                throw new QueryException(
                        "Query references a table outside this collection: " + foreignTable,
                        "Only this collection's data tables can be queried");
            }
            if (sawTableFunction) {
                throw new QueryException(
                        "Query calls a table-valued function",
                        "Only this collection's data tables can be queried");
            }
            if (!sawAllowedTable) {
                throw new QueryException(
                        "Query does not read any of this collection's tables",
                        "Only this collection's data tables can be queried");
            }
        }

        @Override
        protected String extractTableName(Table table) {
            String name = super.extractTableName(table);
            String normalized = normalizeIdentifier(name);
            if (allowedTables.contains(normalized)) {
                sawAllowedTable = true;
            } else if (!inScope.contains(normalized) && foreignTable == null) {
                foreignTable = normalized;
            }
            return name;
        }

        /**
         * {@code FROM generate_series(...)} produces no table node at all, so the
         * whitelist would be satisfied vacuously. No natural-language question about
         * a collection needs one.
         */
        @Override
        public <S> Void visit(TableFunction tableFunction, S context) {
            sawTableFunction = true;
            return super.visit(tableFunction, context);
        }

        @Override
        public <S> Void visit(WithItem<?> withItem, S context) {
            String name = withItem.getAlias() == null
                    ? null
                    : normalizeIdentifier(withItem.getAlias().getName());
            if (name != null && withItem.isRecursive()) {
                inScope.add(name);
            }
            super.visit(withItem, context);
            if (name != null) {
                inScope.add(name);
            }
            return null;
        }

        // A WITH clause is visible only inside the statement it is attached to.
        // Names are only ever added, so retaining the enclosing set restores it.

        @Override
        public <S> Void visit(PlainSelect plainSelect, S context) {
            Set<String> enclosing = Set.copyOf(inScope);
            super.visit(plainSelect, context);
            inScope.retainAll(enclosing);
            return null;
        }

        @Override
        public <S> Void visit(SetOperationList setOperationList, S context) {
            Set<String> enclosing = Set.copyOf(inScope);
            super.visit(setOperationList, context);
            inScope.retainAll(enclosing);
            return null;
        }

        @Override
        public <S> Void visit(ParenthesedSelect parenthesedSelect, S context) {
            Set<String> enclosing = Set.copyOf(inScope);
            super.visit(parenthesedSelect, context);
            inScope.retainAll(enclosing);
            return null;
        }
    }

    /** The collection's main data table plus every (nested) child entity table. */
    private Set<String> allowedTables(String collectionId, List<SchemaColumn> columns) {
        Set<String> tables = new HashSet<>();
        tables.add(DynamicTableRepository.dataTableName(collectionId));
        collectChildTables(collectionId, columns, tables);
        return tables;
    }

    private void collectChildTables(String collectionId, List<SchemaColumn> columns, Set<String> tables) {
        for (SchemaColumn col : columns) {
            if (col.isEntityArray() && col.entitySchema() != null) {
                tables.add(DynamicTableRepository.dataTableName(collectionId, col.entitySchema().name()));
                collectChildTables(collectionId, col.entitySchema().columns(), tables);
            }
        }
    }

    /** Builds a textual description of the collection's tables for the LLM, recursing into entities. */
    private String describeTables(List<SchemaColumn> columns, String tableName, String collectionId) {
        StringBuilder sb = new StringBuilder("Table: \"").append(tableName).append("\"\nColumns:\n");
        List<String> childTables = new ArrayList<>();

        for (SchemaColumn col : columns) {
            if (col.isEntityArray() && col.entitySchema() != null) {
                String childName = DynamicTableRepository.dataTableName(collectionId, col.entitySchema().name());
                childTables.add(describeTables(col.entitySchema().columns(), childName, collectionId)
                        + "  (child table; join its \"_parent_row_id\" to the parent table's \"_row_id\")\n");
            } else {
                sb.append("  \"").append(SqlNameSanitizer.sanitize(col.name()))
                        .append("\" (").append(col.type().toJson()).append(")\n");
            }
        }

        sb.append("\n");
        childTables.forEach(sb::append);
        return sb.toString();
    }

    // ---- Summary ----

    /** Summarization is non-critical: failures fall back to a plain row count. */
    private String summarizeResults(String query, String sql, List<Map<String, Object>> rows) {
        try {
            String rowsJson = objectMapper.writeValueAsString(
                    rows.subList(0, Math.min(rows.size(), SUMMARY_ROW_SAMPLE)));
            String summary = llmClient.callText(
                    PromptTemplates.resultsSummary(query, sql, rows.size(), rowsJson), 0.3, SUMMARY_MAX_TOKENS);
            return summary.isBlank() ? fallbackSummary(rows.size()) : summary;
        } catch (JsonProcessingException | RuntimeException e) {
            log.warn("Result summarization failed, using fallback: {}", e.getMessage());
            return fallbackSummary(rows.size());
        }
    }

    private static String fallbackSummary(int rowCount) {
        return "Found " + rowCount + " results.";
    }
}
