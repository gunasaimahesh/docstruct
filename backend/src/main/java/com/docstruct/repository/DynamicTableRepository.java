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
import com.docstruct.domain.extraction.ExtractionCell;
import com.docstruct.domain.schema.SchemaColumn;
import com.docstruct.exception.ValidationException;
import com.docstruct.util.SqlNameSanitizer;
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
        List<SchemaColumn> valueColumns = columns.stream().filter(c -> !c.isEntityArray()).toList();
        List<SchemaColumn> entityColumns = columns.stream()
                .filter(c -> c.isEntityArray() && c.entitySchema() != null).toList();

        List<String> colNames = new ArrayList<>();
        colNames.add("_document_id");
        colNames.add("_confidence");
        colNames.add("_importance");
        colNames.add("_confidence_json");
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
            for (SchemaColumn col : valueColumns) {
                ExtractionCell cell = cellFor(row, col);
                ConfidenceLevel confidence = cell != null && cell.confidence() != null
                        ? cell.confidence() : ConfidenceLevel.LOW;
                confidenceMap.put(SqlNameSanitizer.sanitize(col.name()), confidence.toJson());
            }

            List<Object> values = new ArrayList<>();
            values.add(documentId);
            values.add(overallConfidence.toJson());
            values.add(ImportanceLevel.HIGH.toJson());
            values.add(toJson(confidenceMap));
            if (parentRowId != null) {
                values.add(parentRowId);
            }
            for (SchemaColumn col : valueColumns) {
                ExtractionCell cell = cellFor(row, col);
                values.add(toSqlValue(cell != null ? cell.value() : null));
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

    /** Updates one cell in the collection's main data table. */
    public void updateCell(String collectionId, long rowId, String columnName, Object value) {
        String tableName = dataTableName(collectionId);
        String safeName = SqlNameSanitizer.sanitize(columnName);
        int updated = jdbcTemplate.update(
                "UPDATE " + quote(tableName) + " SET " + quote(safeName) + " = ? WHERE _row_id = ?",
                toSqlValue(value), rowId);
        if (updated == 0) {
            throw new ValidationException("Row not found: " + rowId);
        }
    }

    /** Lightweight connectivity check used by the health endpoint. */
    public void ping() {
        jdbcTemplate.queryForObject("SELECT 1", Integer.class);
    }
}
