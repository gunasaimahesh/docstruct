package com.docstruct.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import com.docstruct.util.SqlNameSanitizer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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

        GeneratedSql generated = naturalLanguageToSql(query.trim(), tablesSchema);

        DynamicTableRepository.QueryResultRows result;
        try {
            result = dynamicTableRepository.executeSelect(collectionId, generated.sql());
        } catch (DataAccessException e) {
            String message = e.getMostSpecificCause() != null
                    ? e.getMostSpecificCause().getMessage() : e.getMessage();
            throw new QueryException("SQL execution failed: " + message, "Generated SQL: " + generated.sql());
        }

        List<Map<String, Object>> filteredRows = stripInternalColumns(result.rows());
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

    private GeneratedSql naturalLanguageToSql(String query, String tablesSchema) {
        log.info("Translating query to SQL: {}", query);

        JsonNode parsed = llmClient.callJson(
                PromptTemplates.queryToSql(query, tablesSchema), null, SQL_MAX_TOKENS);

        String sql = parsed.path("sql").asText(null);
        if (sql == null || sql.isBlank()) {
            throw new QueryException("AI failed to generate a valid SQL query");
        }

        validateSql(sql);

        log.info("Query translated to SQL: {}", sql);
        return new GeneratedSql(sql, parsed.path("explanation").asText(""));
    }

    /** Defense in depth: only a single read-only SELECT statement is ever executed. */
    private void validateSql(String sql) {
        String trimmed = sql.trim();
        if (!trimmed.toUpperCase().startsWith("SELECT")) {
            throw new QueryException("Only SELECT queries are allowed", "Generated: " + sql);
        }
        var matcher = FORBIDDEN_KEYWORDS.matcher(trimmed);
        if (matcher.find()) {
            throw new QueryException(
                    "Query contains forbidden operation: " + matcher.group(1).toUpperCase(),
                    "Only read-only SELECT queries are supported");
        }
        // Reject statement chaining (a semicolon followed by anything else)
        String withoutTrailing = trimmed.endsWith(";") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        if (withoutTrailing.contains(";")) {
            throw new QueryException("Multiple SQL statements are not allowed", "Generated: " + sql);
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

    private static List<Map<String, Object>> stripInternalColumns(List<Map<String, Object>> rows) {
        return rows.stream()
                .map(row -> {
                    Map<String, Object> filtered = new LinkedHashMap<String, Object>();
                    row.forEach((key, value) -> {
                        if (!key.startsWith("_")) {
                            filtered.put(key, value);
                        }
                    });
                    return filtered;
                })
                .toList();
    }
}
