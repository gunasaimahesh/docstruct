package com.docstruct.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.docstruct.domain.ColumnType;
import com.docstruct.domain.ConfidenceLevel;
import com.docstruct.domain.ImportanceLevel;
import com.docstruct.domain.extraction.DocumentAnalysis;
import com.docstruct.domain.extraction.ExtractionCell;
import com.docstruct.domain.extraction.ExtractionResult;
import com.docstruct.domain.extraction.SchemaMatchResult;
import com.docstruct.domain.schema.DocumentSchema;
import com.docstruct.domain.schema.EntitySchema;
import com.docstruct.domain.schema.SchemaColumn;
import com.docstruct.exception.ExtractionException;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Transforms raw LLM JSON responses into typed extraction results.
 * Lenient by design: the LLM occasionally omits fields or returns plain
 * values instead of cell objects, and this mapper normalizes all of that.
 */
@Component
public class ExtractionResponseMapper {

    /** Maps a schema-inference response into a full {@link ExtractionResult}. */
    public ExtractionResult toExtractionResult(JsonNode raw) {
        List<SchemaColumn> columns = toColumns(raw.path("schema").path("columns"));
        if (columns.isEmpty()) {
            throw new ExtractionException(
                    "Could not infer any schema columns from the document",
                    "The document may not contain structured data, or the format is not recognized.");
        }

        DocumentSchema schema = new DocumentSchema(
                columns,
                raw.path("document_type").asText("unknown"),
                ConfidenceLevel.fromJson(raw.path("schema").path("confidence").asText(null)));

        List<Map<String, ExtractionCell>> rows = toRows(raw.path("rows"), columns);

        return new ExtractionResult(schema, rows, toAnalysis(raw.path("document_analysis")), toWarnings(raw));
    }

    /** Maps a schema-matching response into a {@link SchemaMatchResult}. */
    public SchemaMatchResult toSchemaMatchResult(JsonNode raw, List<SchemaColumn> existingColumns) {
        List<Map<String, ExtractionCell>> rows = toRows(raw.path("rows"), existingColumns);

        List<SchemaColumn> newColumns = new ArrayList<>();
        for (JsonNode col : raw.path("new_columns")) {
            String name = col.path("name").asText(null);
            String type = col.path("type").asText(null);
            if (name != null && type != null) {
                newColumns.add(new SchemaColumn(
                        name,
                        ColumnType.fromJson(type),
                        col.path("description").asText(null),
                        false));
            }
        }

        return new SchemaMatchResult(rows, newColumns, toWarnings(raw));
    }

    // ---- Columns ----

    private List<SchemaColumn> toColumns(JsonNode rawColumns) {
        List<SchemaColumn> columns = new ArrayList<>();
        int index = 0;
        for (JsonNode col : rawColumns) {
            index++;
            ColumnType type = ColumnType.fromJson(col.path("type").asText("text"));
            EntitySchema entitySchema = null;

            if (type == ColumnType.ENTITY_ARRAY && col.has("entitySchema")) {
                JsonNode raw = col.path("entitySchema");
                String name = raw.path("name").asText(col.path("name").asText("entity") + "_entity");
                entitySchema = new EntitySchema(
                        name,
                        raw.path("description").asText(null),
                        toColumns(raw.path("columns")));
            }

            columns.add(new SchemaColumn(
                    col.path("name").asText("column_" + index),
                    type,
                    col.path("description").asText(null),
                    !col.path("required").isBoolean() || col.path("required").asBoolean(),
                    entitySchema));
        }
        return columns;
    }

    // ---- Rows ----

    private List<Map<String, ExtractionCell>> toRows(JsonNode rawRows, List<SchemaColumn> columns) {
        List<Map<String, ExtractionCell>> rows = new ArrayList<>();
        for (JsonNode rawRow : rawRows) {
            Map<String, ExtractionCell> row = new LinkedHashMap<>();
            for (SchemaColumn col : columns) {
                row.put(col.name(), toCell(rawRow.get(col.name()), col));
            }
            rows.add(row);
        }
        return rows;
    }

    private ExtractionCell toCell(JsonNode cell, SchemaColumn col) {
        if (cell == null || cell.isMissingNode()) {
            return ExtractionCell.of(
                    col.isEntityArray() ? List.of() : null,
                    ConfidenceLevel.LOW, ImportanceLevel.LOW);
        }

        if (cell.isObject() && cell.has("value")) {
            return new ExtractionCell(
                    cellValue(cell.get("value"), col),
                    ConfidenceLevel.fromJson(cell.path("confidence").asText(null)),
                    ImportanceLevel.fromJson(cell.path("importance").asText(null)),
                    !cell.path("searchable").isBoolean() || cell.path("searchable").asBoolean(),
                    cell.path("raw_source").isTextual() ? cell.path("raw_source").asText() : null);
        }

        // The LLM returned a plain value instead of a cell object
        return ExtractionCell.of(cellValue(cell, col), ConfidenceLevel.MEDIUM, ImportanceLevel.MEDIUM);
    }

    private Object cellValue(JsonNode value, SchemaColumn col) {
        if (col.isEntityArray()) {
            if (value != null && value.isArray() && col.entitySchema() != null) {
                return toRows(value, col.entitySchema().columns());
            }
            return null;
        }
        return coerceValue(value, col.type());
    }

    /** Coerces a raw JSON value to the expected column type, mirroring the original engine. */
    Object coerceValue(JsonNode value, ColumnType type) {
        if (value == null || value.isNull() || value.isMissingNode()
                || (value.isTextual() && value.asText().isEmpty())) {
            return null;
        }

        return switch (type) {
            case NUMBER, CURRENCY -> {
                if (value.isNumber()) {
                    yield value.doubleValue();
                }
                String cleaned = value.asText().replaceAll("[,$€£¥₹\\s]", "");
                try {
                    yield Double.parseDouble(cleaned);
                } catch (NumberFormatException e) {
                    yield null;
                }
            }
            case BOOLEAN -> {
                if (value.isBoolean()) {
                    yield value.booleanValue();
                }
                yield switch (value.asText().toLowerCase()) {
                    case "true", "yes", "1", "y" -> Boolean.TRUE;
                    case "false", "no", "0", "n" -> Boolean.FALSE;
                    default -> null;
                };
            }
            case ENTITY_ARRAY -> null; // handled separately
            default -> value.isContainerNode() ? value.toString() : value.asText();
        };
    }

    // ---- Misc ----

    private DocumentAnalysis toAnalysis(JsonNode analysis) {
        if (analysis == null || !analysis.isObject()) {
            return null;
        }
        List<String> sections = null;
        if (analysis.path("detected_sections").isArray()) {
            sections = new ArrayList<>();
            for (JsonNode s : analysis.path("detected_sections")) {
                sections.add(s.asText());
            }
        }
        return new DocumentAnalysis(
                textOrNull(analysis, "purpose"),
                textOrNull(analysis, "owner"),
                textOrNull(analysis, "audience"),
                textOrNull(analysis, "useful_data_identified"),
                sections,
                textOrNull(analysis, "ai_summary"));
    }

    private List<String> toWarnings(JsonNode raw) {
        List<String> warnings = new ArrayList<>();
        for (JsonNode w : raw.path("warnings")) {
            warnings.add(w.asText());
        }
        return warnings;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isEmpty() ? value.asText() : null;
    }
}
