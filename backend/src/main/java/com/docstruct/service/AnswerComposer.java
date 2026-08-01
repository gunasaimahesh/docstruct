package com.docstruct.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.docstruct.dto.QueryResponse.CoverageDto;
import com.docstruct.dto.QueryResponse.QueryResultDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Turns raw SQL result rows into a <em>grounded</em> answer.
 *
 * <p>Three things happen here, and nothing here talks to an LLM:
 * <ol>
 *   <li><b>Provenance projection.</b> Each business cell becomes
 *       {@code {value, confidence?, evidence?, rawSource?}} by reading the
 *       {@code _confidence_json} / {@code _evidence_json} bookkeeping columns.
 *       Every {@code _}-prefixed column is then dropped so it can never leak.</li>
 *   <li><b>Deterministic headline.</b> The one-line answer is computed from the
 *       result set, never phrased by a model. Entity-scoped results name the
 *       nested entity ("Found 1 Experience entry"); document/list results use
 *       counts or the single value itself.</li>
 *   <li><b>Coverage.</b> How much of the answer rests on low-confidence
 *       extraction, so the caller can be honest about it.</li>
 * </ol>
 */
@Component
public class AnswerComposer {

    private static final Logger log = LoggerFactory.getLogger(AnswerComposer.class);

    /** Column names produced by SQL aggregate functions — surfaces the "aggregate" answer type. */
    private static final Pattern AGGREGATE_COLUMN = Pattern.compile(
            "\\b(sum|count|avg|average|min|max|total)\\b", Pattern.CASE_INSENSITIVE);

    private static final String LOW = "low";
    /** Synthetic parent-locator column projected on entity-centric rows. */
    static final String PARENT_COLUMN = "parent";

    private final ObjectMapper objectMapper;

    public AnswerComposer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Options that shape how a result set is turned into an answer. */
    public record ComposeOptions(
            boolean excludeLowConfidence,
            /** {@code entries} | {@code documents} | null (documents). */
            String resultUnit,
            /** Nested entity label when {@code resultUnit=entries}. */
            String entityLabel,
            int totalMatchingRows
    ) {
        public static ComposeOptions of(int totalMatchingRows, boolean excludeLowConfidence) {
            return new ComposeOptions(excludeLowConfidence, null, null, totalMatchingRows);
        }

        public static ComposeOptions entries(int totalMatchingRows, boolean excludeLowConfidence,
                                             String entityLabel) {
            return new ComposeOptions(excludeLowConfidence, "entries", entityLabel, totalMatchingRows);
        }
    }

    public QueryResultDto compose(List<Map<String, Object>> rawRows,
                                  String sql,
                                  String explanation,
                                  int totalMatchingRows,
                                  boolean excludeLowConfidence) {
        return compose(rawRows, sql, explanation, ComposeOptions.of(totalMatchingRows, excludeLowConfidence));
    }

    public QueryResultDto compose(List<Map<String, Object>> rawRows,
                                  String sql,
                                  String explanation,
                                  ComposeOptions options) {
        List<String> columns = resolveColumns(rawRows);

        List<ProjectedRow> projected = new ArrayList<>();
        for (Map<String, Object> raw : rawRows) {
            projected.add(projectRow(raw, columns));
        }

        List<ProjectedRow> included = new ArrayList<>();
        int excludedRows = 0;
        for (ProjectedRow row : projected) {
            if (options.excludeLowConfidence() && row.hasLowConfidence()) {
                excludedRows++;
            } else {
                included.add(row);
            }
        }

        List<Map<String, Object>> outputRows = included.stream().map(ProjectedRow::cells).toList();

        boolean entityScoped = "entries".equals(options.resultUnit());
        String answerType = classify(columns, included, entityScoped);
        int effectiveTotal = Math.max(0, options.totalMatchingRows() - excludedRows);
        String headline = buildHeadline(columns, included, answerType, effectiveTotal,
                options.entityLabel(), entityScoped);
        CoverageDto coverage = buildCoverage(projected, included, excludedRows);
        List<String> caveats = buildCaveats(coverage, options.excludeLowConfidence());

        return QueryResultDto.answered(
                columns, outputRows, sql, explanation, headline, headline, answerType, coverage, caveats,
                entityScoped ? "entries" : (options.resultUnit() == null ? "documents" : options.resultUnit()),
                entityScoped ? options.entityLabel() : null);
    }

    // ---- Provenance projection ----

    /**
     * Business columns, first-seen order. The synthetic {@code parent} locator
     * (if present) is placed first so entity-centric tables read as
     * "from whom → which entry".
     */
    private static List<String> resolveColumns(List<Map<String, Object>> rows) {
        Set<String> columns = new LinkedHashSet<>();
        boolean hasParent = false;
        for (Map<String, Object> row : rows) {
            for (String key : row.keySet()) {
                if (key.startsWith("_")) {
                    continue;
                }
                if (PARENT_COLUMN.equals(key)) {
                    hasParent = true;
                } else {
                    columns.add(key);
                }
            }
        }
        List<String> ordered = new ArrayList<>();
        if (hasParent) {
            ordered.add(PARENT_COLUMN);
        }
        ordered.addAll(columns);
        return ordered;
    }

    private ProjectedRow projectRow(Map<String, Object> raw, List<String> columns) {
        JsonNode confidence = parseJson((String) raw.get("_confidence_json"));
        JsonNode evidence = parseJson((String) raw.get("_evidence_json"));

        Map<String, Object> cells = new LinkedHashMap<>();
        boolean lowConfidence = false;
        for (String column : columns) {
            Map<String, Object> cell = new LinkedHashMap<>();
            cell.put("value", raw.get(column));

            // Parent locator is a join projection — no per-cell provenance on the child.
            if (!PARENT_COLUMN.equals(column)) {
                String level = levelFor(confidence, evidence, column);
                if (level != null) {
                    cell.put("confidence", level);
                    lowConfidence = lowConfidence || LOW.equals(level);
                }

                Map<String, Object> publicEvidence = publicEvidence(evidence, column);
                if (!publicEvidence.isEmpty()) {
                    cell.put("evidence", publicEvidence);
                    Object rawSource = publicEvidence.get("rawSource");
                    if (rawSource != null) {
                        cell.put("rawSource", rawSource);
                    }
                }
            }
            cells.put(column, cell);
        }
        return new ProjectedRow(cells, lowConfidence);
    }

    /** Confidence for a column: prefer the confidence map, fall back to the evidence node's level. */
    private static String levelFor(JsonNode confidence, JsonNode evidence, String column) {
        if (confidence != null && confidence.hasNonNull(column)) {
            JsonNode node = confidence.get(column);
            if (node.isTextual()) {
                return node.asText().toLowerCase(Locale.ROOT);
            }
        }
        if (evidence != null && evidence.has(column)) {
            JsonNode node = evidence.get(column);
            if (node.isObject() && node.hasNonNull("level")) {
                return node.get("level").asText().toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    /** The parts of the evidence node safe to expose: page and the raw source snippet. */
    private static Map<String, Object> publicEvidence(JsonNode evidence, String column) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (evidence == null || !evidence.has(column)) {
            return out;
        }
        JsonNode node = evidence.get(column);
        if (node == null || !node.isObject()) {
            return out;
        }
        if (node.hasNonNull("page")) {
            out.put("page", node.get("page").isNumber() ? node.get("page").numberValue() : node.get("page").asText());
        }
        if (node.hasNonNull("rawSource")) {
            out.put("rawSource", node.get("rawSource").asText());
        }
        return out;
    }

    private JsonNode parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.debug("Could not parse provenance JSON, treating as absent: {}", e.getMessage());
            return null;
        }
    }

    // ---- Answer shape ----

    private static String classify(List<String> columns, List<ProjectedRow> rows, boolean entityScoped) {
        // Entity-centric answers are always a list/table of entries, never a bare scalar.
        if (entityScoped) {
            List<String> business = columns.stream().filter(c -> !PARENT_COLUMN.equals(c)).toList();
            return business.size() <= 1 ? "list" : "table";
        }
        int n = rows.size();
        int m = columns.size();
        if (n == 1 && m == 1) {
            return AGGREGATE_COLUMN.matcher(columns.get(0)).find() ? "aggregate" : "single_value";
        }
        if (n == 1) {
            return "single_value";
        }
        if (m <= 1) {
            return "list";
        }
        return "table";
    }

    private static String buildHeadline(List<String> columns, List<ProjectedRow> rows,
                                        String answerType, int total,
                                        String entityLabel, boolean entityScoped) {
        if (entityScoped) {
            return formatEntityHeadline(entityLabel, rows.isEmpty() ? 0 : total);
        }
        if (rows.isEmpty()) {
            return "No matching results";
        }
        if ("single_value".equals(answerType) || "aggregate".equals(answerType)) {
            if (columns.size() == 1) {
                Object value = valueOf(rows.get(0), columns.get(0));
                String text = value == null ? "" : String.valueOf(value);
                return text.isBlank() ? "1 result" : text;
            }
            return "1 result";
        }
        int count = Math.max(total, rows.size());
        return count + (count == 1 ? " result" : " results");
    }

    static String formatEntityHeadline(String entityLabel, int count) {
        String label = (entityLabel == null || entityLabel.isBlank()) ? "entry" : entityLabel.trim();
        if (count <= 0) {
            return "No matching " + label + " entries";
        }
        if (count == 1) {
            return "Found 1 " + label + " entry";
        }
        return "Found " + count + " " + label + " entries";
    }

    private static Object valueOf(ProjectedRow row, String column) {
        Object cell = row.cells().get(column);
        if (cell instanceof Map<?, ?> map) {
            return map.get("value");
        }
        return cell;
    }

    // ---- Coverage ----

    private static CoverageDto buildCoverage(List<ProjectedRow> all,
                                             List<ProjectedRow> included,
                                             int excludedRows) {
        int cellsWithValues = 0;
        int lowConfidenceCells = 0;
        boolean anyProvenance = false;

        for (ProjectedRow row : included) {
            for (Map.Entry<String, Object> entry : row.cells().entrySet()) {
                if (PARENT_COLUMN.equals(entry.getKey())) {
                    continue;
                }
                if (!(entry.getValue() instanceof Map<?, ?> map)) {
                    continue;
                }
                Object value = map.get("value");
                if (value != null && !String.valueOf(value).isBlank()) {
                    cellsWithValues++;
                }
                Object confidence = map.get("confidence");
                if (confidence != null) {
                    anyProvenance = true;
                    if (LOW.equals(confidence)) {
                        lowConfidenceCells++;
                    }
                }
            }
        }

        if (!anyProvenance) {
            return CoverageDto.unverifiable(all.size());
        }
        return CoverageDto.of(
                all.size(),
                included.size(),
                excludedRows,
                cellsWithValues,
                lowConfidenceCells,
                null,
                null,
                null);
    }

    private static List<String> buildCaveats(CoverageDto coverage, boolean excludeLowConfidence) {
        List<String> caveats = new ArrayList<>();
        if (coverage == null) {
            return caveats;
        }
        if (excludeLowConfidence && coverage.excludedRows() > 0) {
            caveats.add(coverage.excludedRows()
                    + (coverage.excludedRows() == 1 ? " row" : " rows")
                    + " with low-confidence values were excluded.");
        } else if (coverage.verifiable() && coverage.lowConfidenceCells() > 0) {
            caveats.add("Some values were extracted with low confidence — treat them with caution.");
        }
        return caveats;
    }

    /** A row after provenance projection: business cells only, plus whether any cell is low-confidence. */
    private record ProjectedRow(Map<String, Object> cells, boolean hasLowConfidence) {
    }
}
