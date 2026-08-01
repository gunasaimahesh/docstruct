package com.docstruct.repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.docstruct.domain.ColumnType;
import com.docstruct.domain.ConfidenceLevel;
import com.docstruct.domain.ImportanceLevel;
import com.docstruct.domain.extraction.CellEvidence;
import com.docstruct.domain.extraction.ExtractionCell;
import com.docstruct.domain.schema.SchemaColumn;
import com.docstruct.exception.RowNotFoundException;
import com.docstruct.exception.ValidationException;
import com.docstruct.util.SqlNameSanitizer;
import com.docstruct.util.ValueParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Manages the dynamically-created data tables — one per collection, plus one
 * child table per nested entity_array column. These tables cannot be modeled
 * with JPA because their columns are inferred by the LLM at upload time,
 * so all access goes through JdbcTemplate with sanitized, quoted identifiers.
 */
@Repository
public class DynamicTableRepository {

    /**
     * Per-cell source attribution (page, chunk, score, note), stored alongside the
     * values so an exported or queried row can still be traced back to the document.
     */
    static final String EVIDENCE_COLUMN = "_evidence_json";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public DynamicTableRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    // ---- Table naming ----

    public static String dataTableName(String collectionId) {
        return "data_" + collectionId.replace("-", "_");
    }

    public static String dataTableName(String collectionId, String entityName) {
        return dataTableName(collectionId) + "_" + SqlNameSanitizer.sanitize(entityName);
    }

    /** Quotes an identifier, escaping embedded quotes so the result is always a single identifier. */
    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static String pgType(ColumnType type) {
        return switch (type) {
            case NUMBER, CURRENCY -> "double precision";
            case BOOLEAN -> "boolean";
            default -> "text";
        };
    }

    // ---- Table lifecycle ----

    /** Creates the data table for a collection, plus child tables for nested entities (recursively). */
    public void createDataTables(String collectionId, List<SchemaColumn> columns) {
        createTable(collectionId, columns, null, null);
    }

    private void createTable(String collectionId, List<SchemaColumn> columns,
                             String parentTableName, String entityName) {
        String tableName = entityName == null
                ? dataTableName(collectionId)
                : dataTableName(collectionId, entityName);

        List<String> columnDefs = new ArrayList<>();
        columnDefs.add("_row_id BIGSERIAL PRIMARY KEY");
        columnDefs.add("_document_id VARCHAR(36) NOT NULL REFERENCES documents(id) ON DELETE CASCADE");
        columnDefs.add("_confidence TEXT NOT NULL DEFAULT 'medium'");
        columnDefs.add("_importance TEXT NOT NULL DEFAULT 'medium'");
        columnDefs.add("_confidence_json TEXT NOT NULL DEFAULT '{}'");
        columnDefs.add(quote(EVIDENCE_COLUMN) + " TEXT NOT NULL DEFAULT '{}'");

        if (parentTableName != null) {
            columnDefs.add("_parent_row_id BIGINT NOT NULL REFERENCES "
                    + quote(parentTableName) + "(_row_id) ON DELETE CASCADE");
        }

        List<SchemaColumn> childEntities = new ArrayList<>();
        for (SchemaColumn col : columns) {
            if (col.isEntityArray() && col.entitySchema() != null) {
                childEntities.add(col);
            } else {
                columnDefs.add(quote(SqlNameSanitizer.sanitize(col.name())) + " " + pgType(col.type()));
            }
        }

        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS " + quote(tableName)
                + " (" + String.join(", ", columnDefs) + ")");

        for (SchemaColumn child : childEntities) {
            createTable(collectionId, child.entitySchema().columns(), tableName, child.entitySchema().name());
        }
    }

    /** Adds a column to an existing data table (schema evolution). */
    public void addColumn(String collectionId, SchemaColumn column) {
        String tableName = dataTableName(collectionId);
        String safeName = SqlNameSanitizer.sanitize(column.name());
        jdbcTemplate.execute("ALTER TABLE " + quote(tableName)
                + " ADD COLUMN IF NOT EXISTS " + quote(safeName) + " " + pgType(column.type()));
    }

    /** Drops the collection's data table and all its child entity tables. */
    public void dropTables(String collectionId, List<SchemaColumn> columns) {
        if (columns != null) {
            for (SchemaColumn col : columns) {
                if (col.isEntityArray() && col.entitySchema() != null) {
                    jdbcTemplate.execute("DROP TABLE IF EXISTS "
                            + quote(dataTableName(collectionId, col.entitySchema().name())) + " CASCADE");
                }
            }
        }
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + quote(dataTableName(collectionId)) + " CASCADE");
    }

    // ---- Row operations ----

    /**
     * Inserts extracted rows into the collection's data tables, recursing into
     * nested entity rows. Returns the total number of rows inserted.
     */
    @Transactional
    public int insertRows(String collectionId, String documentId, List<SchemaColumn> columns,
                          List<Map<String, ExtractionCell>> rows, ConfidenceLevel overallConfidence) {
        return insertRecursive(collectionId, documentId, columns, rows,
                dataTableName(collectionId), null, overallConfidence);
    }

    private int insertRecursive(String collectionId, String documentId, List<SchemaColumn> columns,
                                List<Map<String, ExtractionCell>> rows, String tableName,
                                Long parentRowId, ConfidenceLevel overallConfidence) {
        ensureEvidenceColumn(tableName);

        List<SchemaColumn> valueColumns = columns.stream().filter(c -> !c.isEntityArray()).toList();
        List<SchemaColumn> entityColumns = columns.stream()
                .filter(c -> c.isEntityArray() && c.entitySchema() != null).toList();

        List<String> colNames = new ArrayList<>();
        colNames.add("_document_id");
        colNames.add("_confidence");
        colNames.add("_importance");
        colNames.add("_confidence_json");
        colNames.add(EVIDENCE_COLUMN);
        if (parentRowId != null) {
            colNames.add("_parent_row_id");
        }
        valueColumns.forEach(c -> colNames.add(SqlNameSanitizer.sanitize(c.name())));

        String sql = "INSERT INTO " + quote(tableName) + " ("
                + colNames.stream().map(DynamicTableRepository::quote).reduce((a, b) -> a + ", " + b).orElseThrow()
                + ") VALUES (" + String.join(", ", colNames.stream().map(c -> "?").toList())
                + ") RETURNING _row_id";

        int inserted = 0;
        for (Map<String, ExtractionCell> row : rows) {
            Map<String, String> confidenceMap = new LinkedHashMap<>();
            Map<String, Map<String, Object>> evidenceMap = new LinkedHashMap<>();
            for (SchemaColumn col : valueColumns) {
                ExtractionCell cell = cellFor(row, col);
                ConfidenceLevel confidence = cell != null && cell.confidence() != null
                        ? cell.confidence() : ConfidenceLevel.LOW;
                String safeName = SqlNameSanitizer.sanitize(col.name());
                confidenceMap.put(safeName, confidence.toJson());
                evidenceMap.put(safeName, evidenceOf(cell, confidence));
            }

            List<Object> values = new ArrayList<>();
            values.add(documentId);
            values.add(overallConfidence.toJson());
            values.add(ImportanceLevel.HIGH.toJson());
            values.add(toJson(confidenceMap));
            values.add(toJson(evidenceMap));
            if (parentRowId != null) {
                values.add(parentRowId);
            }
            for (SchemaColumn col : valueColumns) {
                ExtractionCell cell = cellFor(row, col);
                values.add(toSqlValue(cell != null ? cell.value() : null, col.type()));
            }

            Long rowId = jdbcTemplate.queryForObject(sql, Long.class, values.toArray());
            inserted++;

            for (SchemaColumn entityCol : entityColumns) {
                ExtractionCell cell = cellFor(row, entityCol);
                if (cell != null && cell.value() instanceof List<?> childRows && !childRows.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, ExtractionCell>> typed = (List<Map<String, ExtractionCell>>) childRows;
                    inserted += insertRecursive(collectionId, documentId,
                            entityCol.entitySchema().columns(), typed,
                            dataTableName(collectionId, entityCol.entitySchema().name()),
                            rowId, overallConfidence);
                }
            }
        }
        return inserted;
    }

    /**
     * Backfills the evidence column on tables created before source attribution
     * existed. Idempotent, so it is safe to call on every insert batch — cheaper
     * than a migration framework for a column that only this class reads or writes.
     */
    private void ensureEvidenceColumn(String tableName) {
        jdbcTemplate.execute("ALTER TABLE " + quote(tableName)
                + " ADD COLUMN IF NOT EXISTS " + quote(EVIDENCE_COLUMN) + " TEXT NOT NULL DEFAULT '{}'");
    }

    /** Flattens a cell's verified confidence and citation into the stored evidence entry. */
    private static Map<String, Object> evidenceOf(ExtractionCell cell, ConfidenceLevel confidence) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("level", confidence.toJson());
        if (cell == null || cell.evidence() == null) {
            return entry;
        }

        CellEvidence evidence = cell.evidence();
        if (evidence.score() != null) {
            entry.put("score", evidence.score());
        }
        if (evidence.page() != null) {
            entry.put("page", evidence.page());
        }
        if (evidence.chunk() != null) {
            entry.put("chunk", evidence.chunk());
        }
        if (evidence.note() != null) {
            entry.put("note", evidence.note());
        }
        if (cell.rawSource() != null) {
            entry.put("rawSource", cell.rawSource());
        }
        return entry;
    }

    private static ExtractionCell cellFor(Map<String, ExtractionCell> row, SchemaColumn col) {
        ExtractionCell cell = row.get(col.name());
        return cell != null ? cell : row.get(SqlNameSanitizer.sanitize(col.name()));
    }

    private Object toSqlValue(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        return toJson(value);
    }

    /**
     * Binds a value to the type its column was created with. The column types and the
     * values both come from the LLM but describe different things — the shape of the
     * collection and the content of one document — so they can disagree, typically when
     * a numeric column is answered with prose. Storing null for a value the column cannot
     * hold keeps one odd field from failing the whole upload, and the original is still
     * in the document's raw JSON.
     */
    private Object toSqlValue(Object value, ColumnType type) {
        return switch (type) {
            case NUMBER, CURRENCY -> value instanceof Number number
                    ? number
                    : value instanceof String text ? ValueParser.parseNumber(text) : null;
            case BOOLEAN -> value instanceof Boolean flag
                    ? flag
                    : value instanceof String text ? ValueParser.parseBoolean(text) : null;
            default -> toSqlValue(value);
        };
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize value to JSON", e);
        }
    }

    // ---- Reads ----

    public record DataPage(List<Map<String, Object>> rows, long total) {
    }

    /**
     * Reads a page of rows from a collection's data table.
     * {@code tableName} may target a child entity table but must belong to the collection.
     */
    public DataPage getRows(String collectionId, String tableName, int limit, int offset) {
        String resolved = resolveTableName(collectionId, tableName);
        try {
            Long total = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + quote(resolved), Long.class);
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT * FROM " + quote(resolved) + " ORDER BY _row_id ASC LIMIT ? OFFSET ?",
                    limit, offset);
            return new DataPage(rows, total != null ? total : 0);
        } catch (BadSqlGrammarException e) {
            // "relation does not exist": the table has not been created yet
            // (no documents ingested). Any other failure propagates normally.
            return new DataPage(List.of(), 0);
        }
    }

    private String resolveTableName(String collectionId, String tableName) {
        String mainTable = dataTableName(collectionId);
        if (tableName == null || tableName.isBlank()) {
            return mainTable;
        }
        boolean belongsToCollection = tableName.equals(mainTable) || tableName.startsWith(mainTable + "_");
        if (!belongsToCollection || !tableName.matches("[a-z0-9_]+")) {
            throw new ValidationException("Invalid table name: " + tableName);
        }
        return tableName;
    }

    public record QueryResultRows(List<String> columns, List<Map<String, Object>> rows) {
    }

    /** Executes a (pre-validated, SELECT-only, table-whitelisted) query against the collection's data tables. */
    @Transactional(readOnly = true)
    public QueryResultRows executeSelect(String sql) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        List<String> columns = rows.isEmpty()
                ? List.of()
                : rows.get(0).keySet().stream().filter(k -> !k.startsWith("_")).toList();
        return new QueryResultRows(columns, rows);
    }

    /**
     * A filter clause whose identifiers are already sanitized and quoted.
     * Values are always bound — never interpolated into the SQL string.
     */
    public record FilterClause(String sqlFragment, List<Object> params) {
        public FilterClause {
            params = List.copyOf(params);
        }
    }

    public record FilterQuery(
            List<FilterClause> clauses,
            boolean matchAny,
            String sortColumn,
            boolean sortDescending,
            int limit,
            int offset
    ) {
    }

    public record FilterPage(List<Map<String, Object>> rows, long total, String sql) {
    }

    /**
     * Distinct non-empty values for one already-sanitized column.
     * {@code entityName} null → main data table; otherwise the child entity table.
     * Used by the filter UI for categorical roles — never by the LLM.
     */
    @Transactional(readOnly = true)
    public List<String> distinctValues(String collectionId, String entityName,
                                       String sanitizedColumn, int limit) {
        String table = entityName == null || entityName.isBlank()
                ? dataTableName(collectionId)
                : dataTableName(collectionId, entityName);
        String quoted = quote(sanitizedColumn);
        String sql = "SELECT DISTINCT CAST(" + quoted + " AS TEXT) AS v"
                + " FROM " + quote(table)
                + " WHERE " + quoted + " IS NOT NULL"
                + " AND TRIM(CAST(" + quoted + " AS TEXT)) <> ''"
                + " ORDER BY v ASC"
                + " LIMIT ?";
        try {
            return jdbcTemplate.query(sql, (rs, rowNum) -> rs.getString("v"), limit);
        } catch (BadSqlGrammarException e) {
            return List.of();
        }
    }

    /**
     * Runs a structured filter against the collection's main data table.
     * Nested {@code entity_array} conditions arrive as correlated {@code EXISTS}
     * fragments that reference the {@code main} alias. Every value is a bind
     * parameter; column/table names arrive already sanitized by the service.
     */
    @Transactional(readOnly = true)
    public FilterPage filterRows(String collectionId, FilterQuery query) {
        String table = dataTableName(collectionId);
        StringBuilder where = new StringBuilder();
        List<Object> params = new ArrayList<>();

        if (!query.clauses().isEmpty()) {
            String joiner = query.matchAny() ? " OR " : " AND ";
            where.append(" WHERE ");
            for (int i = 0; i < query.clauses().size(); i++) {
                if (i > 0) {
                    where.append(joiner);
                }
                FilterClause clause = query.clauses().get(i);
                where.append('(').append(clause.sqlFragment()).append(')');
                params.addAll(clause.params());
            }
        }

        // Alias required so EXISTS subqueries can correlate on main._row_id.
        String from = " FROM " + quote(table) + " AS main";
        String orderBy = " ORDER BY main." + quote(query.sortColumn())
                + (query.sortDescending() ? " DESC" : " ASC");

        String countSql = "SELECT COUNT(*)" + from + where;
        String selectSql = "SELECT main.*" + from + where + orderBy + " LIMIT ? OFFSET ?";

        try {
            Long total = jdbcTemplate.queryForObject(countSql, Long.class, params.toArray());
            List<Object> pageParams = new ArrayList<>(params);
            pageParams.add(query.limit());
            pageParams.add(query.offset());
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(selectSql, pageParams.toArray());
            return new FilterPage(rows, total != null ? total : 0, selectSql);
        } catch (BadSqlGrammarException e) {
            return new FilterPage(List.of(), 0, selectSql);
        }
    }

    /** Updates one cell in the collection's main data table. */
    public void updateCell(String collectionId, long rowId, String columnName, Object value) {
        String tableName = dataTableName(collectionId);
        String safeName = SqlNameSanitizer.sanitize(columnName);
        int updated = jdbcTemplate.update(
                "UPDATE " + quote(tableName) + " SET " + quote(safeName) + " = ? WHERE _row_id = ?",
                toSqlValue(value), rowId);
        if (updated == 0) {
            throw new RowNotFoundException(rowId);
        }
    }

    /** Lightweight connectivity check used by the health endpoint. */
    public void ping() {
        jdbcTemplate.queryForObject("SELECT 1", Integer.class);
    }
}
