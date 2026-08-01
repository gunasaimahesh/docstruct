package com.docstruct.llm;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.docstruct.domain.ColumnType;
import com.docstruct.domain.ConfidenceLevel;
import com.docstruct.domain.ImportanceLevel;
import com.docstruct.domain.extraction.CellEvidence;
import com.docstruct.domain.extraction.DocumentAnalysis;
import com.docstruct.domain.extraction.DocumentTypeInfo;
import com.docstruct.domain.extraction.ExtractionCell;
import com.docstruct.domain.extraction.ExtractionResult;
import com.docstruct.domain.extraction.KnowledgeSection;
import com.docstruct.domain.extraction.SchemaMatchResult;
import com.docstruct.domain.schema.DocumentSchema;
import com.docstruct.domain.schema.EntitySchema;
import com.docstruct.domain.schema.SchemaColumn;
import com.docstruct.exception.ExtractionException;
import com.docstruct.util.ConfidenceScorer;
import com.docstruct.util.ValueParser;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Transforms raw LLM JSON responses into typed extraction results.
 * Lenient by design: the LLM occasionally omits fields or returns plain
 * values instead of cell objects, and this mapper normalizes all of that.
 *
 * Leniency stops at trust. Every mapped cell is passed through a
 * {@link ConfidenceScorer}, which replaces the model's self-reported confidence
 * with a level derived from verifying the cited chunk and the quoted source text
 * against the actual document.
 */
@Component
public class ExtractionResponseMapper {

    /** Maps a schema-inference response into a full {@link ExtractionResult}. */
    public ExtractionResult toExtractionResult(JsonNode raw, ConfidenceScorer scorer) {
        List<SchemaColumn> declared = toColumns(raw.path("schema").path("columns"));
        if (declared.isEmpty()) {
            throw new ExtractionException(
                    "Could not infer any schema columns from the document",
                    "The document may not contain structured data, or the format is not recognized.");
        }

        // Models sometimes put a real nested table in "value" while typing the column
        // as text. Promote those before mapping so the UI gets a table, not a blob.
        JsonNode firstRow = raw.path("rows").isArray() && !raw.path("rows").isEmpty()
                ? raw.path("rows").get(0) : null;
        List<SchemaColumn> columns = promoteStructuredColumns(declared, firstRow);

        DocumentSchema schema = new DocumentSchema(
                columns,
                raw.path("document_type").asText("unknown"),
                ConfidenceLevel.fromJson(raw.path("schema").path("confidence").asText(null)));

        List<Map<String, ExtractionCell>> rows = toRows(raw.path("rows"), columns, scorer);

        return new ExtractionResult(schema, rows,
                toAnalysis(raw, columns, schema.documentType()), toWarnings(raw));
    }

    /**
     * Applies a structure-repair response: replaces selected text columns with
     * entity_array tables. Columns absent from {@code repair} are left unchanged.
     */
    public ExtractionResult applyRestructure(ExtractionResult current, JsonNode repair,
                                            ConfidenceScorer scorer) {
        JsonNode restructured = repair.path("restructured");
        if (!restructured.isObject() || restructured.isEmpty()) {
            return current;
        }

        List<SchemaColumn> columns = new ArrayList<>();
        for (SchemaColumn column : current.schema().columns()) {
            JsonNode replacement = restructured.get(column.name());
            if (replacement == null || !replacement.has("entitySchema")) {
                columns.add(column);
                continue;
            }
            EntitySchema entitySchema = new EntitySchema(
                    replacement.path("entitySchema").path("name")
                            .asText(column.name() + "_entity"),
                    textOrNull(replacement.path("entitySchema"), "description"),
                    toColumns(replacement.path("entitySchema").path("columns")));
            if (entitySchema.columns().isEmpty()) {
                columns.add(column);
                continue;
            }
            columns.add(new SchemaColumn(
                    column.name(), ColumnType.ENTITY_ARRAY, column.description(),
                    column.required(), entitySchema));
        }

        List<Map<String, ExtractionCell>> rows = new ArrayList<>();
        for (Map<String, ExtractionCell> existingRow : current.rows()) {
            Map<String, ExtractionCell> row = new LinkedHashMap<>();
            for (SchemaColumn column : columns) {
                JsonNode replacement = restructured.get(column.name());
                if (column.isEntityArray() && replacement != null && replacement.path("rows").isArray()) {
                    row.put(column.name(), scorer.score(
                            toCell(syntheticEntityCell(replacement), column, scorer), column));
                } else {
                    row.put(column.name(), existingRow.get(column.name()) != null
                            ? existingRow.get(column.name())
                            : ExtractionCell.of(null, ConfidenceLevel.LOW, ImportanceLevel.LOW));
                }
            }
            scorer.crossCheckTotals(row, columns);
            rows.add(row);
        }

        // Knowledge section field names are unchanged — only the column type becomes
        // entity_array — so the existing section layout still resolves.
        return new ExtractionResult(
                current.schema().withColumns(columns), rows, current.analysis(), current.warnings());
    }

    /**
     * Text columns that look like flattened multi-attribute records — candidates for
     * the structure-repair LLM pass. Identity fields and prose summaries are excluded.
     */
    public List<Map<String, Object>> flatRestructureCandidates(ExtractionResult result) {
        if (result.rows().isEmpty()) {
            return List.of();
        }
        Map<String, ExtractionCell> row = result.rows().get(0);
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (SchemaColumn column : result.schema().columns()) {
            if (column.isEntityArray() || isScalarIdentityOrProse(column)) {
                continue;
            }
            ExtractionCell cell = row.get(column.name());
            if (cell == null || !(cell.value() instanceof String text)) {
                continue;
            }
            String trimmed = text.strip();
            if (trimmed.length() < 40 || trimmed.split("\\s+").length < 6) {
                continue;
            }
            Map<String, Object> candidate = new LinkedHashMap<>();
            candidate.put("column", column.name());
            candidate.put("value", trimmed);
            if (cell.evidence() != null) {
                candidate.put("page", cell.evidence().page());
                candidate.put("chunk", cell.evidence().chunk());
            }
            if (cell.rawSource() != null) {
                candidate.put("raw_source", cell.rawSource());
            }
            candidates.add(candidate);
        }
        return candidates;
    }

    /** Maps a schema-matching response into a {@link SchemaMatchResult}. */
    public SchemaMatchResult toSchemaMatchResult(JsonNode raw, DocumentSchema existingSchema,
                                                ConfidenceScorer scorer) {
        List<SchemaColumn> existingColumns = existingSchema.columns();
        List<Map<String, ExtractionCell>> rows = toRows(raw.path("rows"), existingColumns, scorer);

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

        return new SchemaMatchResult(rows, newColumns, toWarnings(raw),
                toAnalysis(raw, existingColumns, existingSchema.documentType()));
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

    /**
     * When the model types a column as text/email/… but puts an array of objects (or a
     * multi-key object) in the value, rewrite it to entity_array using the object keys
     * as nested columns. Document-agnostic — driven only by the JSON shape.
     */
    private List<SchemaColumn> promoteStructuredColumns(List<SchemaColumn> declared, JsonNode firstRow) {
        if (firstRow == null || !firstRow.isObject()) {
            return declared;
        }
        List<SchemaColumn> promoted = new ArrayList<>(declared.size());
        for (SchemaColumn column : declared) {
            if (column.isEntityArray()) {
                promoted.add(column);
                continue;
            }
            JsonNode value = valueNode(firstRow.get(column.name()));
            EntitySchema inferred = inferEntitySchema(column.name(), value);
            if (inferred == null) {
                promoted.add(column);
            } else {
                promoted.add(new SchemaColumn(
                        column.name(), ColumnType.ENTITY_ARRAY, column.description(),
                        column.required(), inferred));
            }
        }
        return promoted;
    }

    private EntitySchema inferEntitySchema(String columnName, JsonNode value) {
        JsonNode sample = null;
        if (value != null && value.isArray() && !value.isEmpty()) {
            JsonNode first = value.get(0);
            // Nested row as { "Col": { "value": ... } } or plain { "Col": "..." }
            if (first.isObject()) {
                sample = first;
            }
        } else if (value != null && value.isObject() && !value.has("value") && value.size() >= 2) {
            sample = value;
        }
        if (sample == null) {
            return null;
        }

        List<SchemaColumn> nested = new ArrayList<>();
        sample.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            if ("confidence".equals(key) || "importance".equals(key)
                    || "page".equals(key) || "chunk".equals(key)
                    || "raw_source".equals(key) || "searchable".equals(key)) {
                return;
            }
            nested.add(new SchemaColumn(key, ColumnType.TEXT, null, false));
        });
        if (nested.size() < 2 && !(value != null && value.isArray() && value.size() >= 1 && nested.size() >= 1)) {
            // A single-key object is not a multi-attribute record; an array of
            // single-key objects (list of items) still deserves a one-column table.
            if (!(value != null && value.isArray() && nested.size() == 1)) {
                return null;
            }
        }
        if (nested.isEmpty()) {
            return null;
        }
        return new EntitySchema(columnName + "_entity", null, nested);
    }

    private static JsonNode valueNode(JsonNode cell) {
        if (cell == null || cell.isMissingNode() || cell.isNull()) {
            return null;
        }
        return cell.isObject() && cell.has("value") ? cell.get("value") : cell;
    }

    private static JsonNode syntheticEntityCell(JsonNode replacement) {
        // Build a cell-shaped node { "value": <rows>, "confidence": "high" } for toCell.
        com.fasterxml.jackson.databind.node.ObjectNode cell =
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        cell.set("value", replacement.path("rows"));
        cell.put("confidence", "high");
        cell.put("importance", "medium");
        cell.put("searchable", true);
        return cell;
    }

    /**
     * Fields that should stay scalars even when long: contact identity and prose
     * narratives. Everything else long enough is a restructure candidate.
     */
    private static boolean isScalarIdentityOrProse(SchemaColumn column) {
        if (column.type() == ColumnType.EMAIL || column.type() == ColumnType.URL) {
            return true;
        }
        String key = matchKey(column.name());
        if (key.contains("summary") || key.contains("abstract") || key.contains("description")
                || key.contains("narrative") || key.contains("overview") || key.contains("purpose")
                || key.contains("comment") || key.equals("note") || key.equals("notes")) {
            return true;
        }
        return Set.of(
                "name", "fullname", "candidatename", "email", "emailaddress",
                "phone", "phonenumber", "mobile", "mobilenumber", "location", "address", "city",
                "linkedin", "linkedinurl", "linkedinprofile", "github", "githuburl",
                "website", "portfolio").contains(key);
    }

    // ---- Rows ----

    private List<Map<String, ExtractionCell>> toRows(JsonNode rawRows, List<SchemaColumn> columns,
                                                    ConfidenceScorer scorer) {
        List<Map<String, ExtractionCell>> rows = new ArrayList<>();
        for (JsonNode rawRow : rawRows) {
            Map<String, ExtractionCell> row = new LinkedHashMap<>();
            for (SchemaColumn col : columns) {
                row.put(col.name(), scorer.score(toCell(rawRow.get(col.name()), col, scorer), col));
            }
            // Runs after the whole row exists: it compares fields against each other.
            scorer.crossCheckTotals(row, columns);
            rows.add(row);
        }
        return rows;
    }

    private ExtractionCell toCell(JsonNode cell, SchemaColumn col, ConfidenceScorer scorer) {
        if (cell == null || cell.isMissingNode()) {
            return ExtractionCell.of(
                    col.isEntityArray() ? List.of() : null,
                    ConfidenceLevel.LOW, ImportanceLevel.LOW);
        }

        if (cell.isObject() && cell.has("value")) {
            return new ExtractionCell(
                    cellValue(cell.get("value"), col, scorer),
                    ConfidenceLevel.fromJson(cell.path("confidence").asText(null)),
                    ImportanceLevel.fromJson(cell.path("importance").asText(null)),
                    !cell.path("searchable").isBoolean() || cell.path("searchable").asBoolean(),
                    cell.path("raw_source").isTextual() ? cell.path("raw_source").asText() : null,
                    citation(cell));
        }

        // The LLM returned a plain value instead of a cell object
        return ExtractionCell.of(cellValue(cell, col, scorer), ConfidenceLevel.MEDIUM, ImportanceLevel.MEDIUM);
    }

    /** The claimed source location, as reported. The scorer decides whether to believe it. */
    private CellEvidence citation(JsonNode cell) {
        Integer page = cell.path("page").isIntegralNumber() ? cell.path("page").asInt() : null;
        Integer chunk = cell.path("chunk").isIntegralNumber() ? cell.path("chunk").asInt() : null;
        return page == null && chunk == null ? null : new CellEvidence(page, chunk, null, null);
    }

    private Object cellValue(JsonNode value, SchemaColumn col, ConfidenceScorer scorer) {
        if (col.isEntityArray()) {
            if (value == null || col.entitySchema() == null) {
                return null;
            }
            if (value.isArray()) {
                return toRows(value, col.entitySchema().columns(), scorer);
            }
            // Promoted from a single multi-key object — treat as one nested row.
            if (value.isObject()) {
                com.fasterxml.jackson.databind.node.ArrayNode array =
                        com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
                array.add(value);
                return toRows(array, col.entitySchema().columns(), scorer);
            }
            return null;
        }
        return coerceValue(value, col.type());
    }

    /** Coerces a raw JSON value to the expected column type; unparsable values become null. */
    Object coerceValue(JsonNode value, ColumnType type) {
        if (value == null || value.isNull() || value.isMissingNode()
                || (value.isTextual() && value.asText().isEmpty())) {
            return null;
        }

        return switch (type) {
            case NUMBER, CURRENCY -> value.isNumber()
                    ? Double.valueOf(value.doubleValue())
                    : ValueParser.parseNumber(value.asText());
            case BOOLEAN -> value.isBoolean()
                    ? Boolean.valueOf(value.booleanValue())
                    : ValueParser.parseBoolean(value.asText());
            case ENTITY_ARRAY -> null; // handled separately
            default -> value.isContainerNode() ? value.toString() : value.asText();
        };
    }

    // ---- Misc ----

    /**
     * The document's semantic layer. Both extraction prompts report the reader-facing
     * type and section layout at the top level of the response; the narrative fields
     * live in the nested analysis block, which only schema inference asks for.
     */
    private DocumentAnalysis toAnalysis(JsonNode raw, List<SchemaColumn> columns, String fallbackType) {
        JsonNode analysis = raw.path("document_analysis");
        DocumentTypeInfo documentType = toDocumentType(raw.path("document_type_info"), fallbackType);
        List<KnowledgeSection> knowledgeSections = toKnowledgeSections(raw.path("knowledge_sections"), columns);

        if (!analysis.isObject() && documentType == null && knowledgeSections.isEmpty()) {
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
                textOrNull(analysis, "ai_summary"),
                documentType,
                knowledgeSections);
    }

    /**
     * The reader-facing type is what the UI leads with, so a response that omits it
     * falls back to the snake_case schema type rather than leaving the document unlabelled.
     */
    private DocumentTypeInfo toDocumentType(JsonNode raw, String fallbackType) {
        String name = textOrNull(raw, "name");
        if (name == null) {
            name = titleCase(fallbackType);
        }
        return name == null ? null : new DocumentTypeInfo(name, textOrNull(raw, "category"));
    }

    /**
     * Section field names are resolved against the columns that actually exist: a section
     * pointing at a column the schema does not have would render as an empty row, and one
     * left with no resolvable fields is dropped. A column named twice stays in the first
     * section that claimed it, so no value is shown in two places.
     *
     * Completeness is enforced here, not left to the model: every schema column must appear
     * in exactly one section. Columns the model forgot are appended as their own sections
     * titled from the column name — no per-document-type templates, just schema coverage.
     * That is what makes Knowledge consistent across uploads.
     */
    private List<KnowledgeSection> toKnowledgeSections(JsonNode rawSections, List<SchemaColumn> columns) {
        Map<String, String> columnsByKey = new LinkedHashMap<>();
        for (SchemaColumn column : columns) {
            columnsByKey.putIfAbsent(matchKey(column.name()), column.name());
        }

        List<KnowledgeSection> sections = new ArrayList<>();
        Set<String> assigned = new HashSet<>();
        if (rawSections.isArray()) {
            for (JsonNode rawSection : rawSections) {
                List<String> fields = new ArrayList<>();
                for (JsonNode field : rawSection.path("fields")) {
                    String column = columnsByKey.get(matchKey(field.asText()));
                    if (column != null && assigned.add(column)) {
                        fields.add(column);
                    }
                }
                String title = textOrNull(rawSection, "title");
                if (title != null && !fields.isEmpty()) {
                    sections.add(new KnowledgeSection(title, textOrNull(rawSection, "description"), fields));
                }
            }
        }
        return ensureCompleteSectionCoverage(sections, columns, assigned);
    }

    /**
     * Appends one section per schema column the model left unassigned, in schema order.
     * Titles come from the column name (humanized), never from a fixed type checklist.
     */
    private static List<KnowledgeSection> ensureCompleteSectionCoverage(
            List<KnowledgeSection> proposed, List<SchemaColumn> columns, Set<String> assigned) {
        List<KnowledgeSection> sections = new ArrayList<>(proposed);
        for (SchemaColumn column : columns) {
            if (assigned.contains(column.name())) {
                continue;
            }
            String title = titleCase(column.name());
            if (title == null) {
                title = column.name();
            }
            sections.add(new KnowledgeSection(title, column.description(), List.of(column.name())));
            assigned.add(column.name());
        }
        return sections;
    }

    /** Matches "total_income", "Total Income" and "totalIncome" to the same column. */
    private static String matchKey(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** "income_tax_return" reads as "Income Tax Return". */
    private static String titleCase(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        StringBuilder result = new StringBuilder();
        for (String word : value.replace('_', ' ').replace('-', ' ').trim().split("\\s+")) {
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
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
