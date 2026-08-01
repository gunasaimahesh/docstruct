package com.docstruct.dto;

import java.util.List;
import java.util.Map;

public record QueryResponse(boolean success, QueryResultDto result) {

    /**
     * A query either ran or was refused as not being a question about the data.
     * A refusal is a successful response carrying {@code answerable = false} and a
     * {@code reason}, not an error: nothing went wrong, the input was not a question.
     *
     * <p>Answered results are grounded: each supporting cell carries confidence and
     * evidence when available, the {@code headline} is computed deterministically
     * from the full result set, and {@code coverage} discloses how much of the
     * answer rests on low-confidence extraction.
     */
    public record QueryResultDto(
            List<String> columns,
            /** Supporting rows: each cell is {@code {value, confidence?, evidence?, rawSource?}}. */
            List<Map<String, Object>> rows,
            int rowCount,
            String generatedSql,
            String explanation,
            /** Deterministic one-line answer computed from the full result set. */
            String headline,
            /** Optional natural-language phrasing of the same facts; never invents numbers. */
            String summary,
            /** {@code single_value} | {@code list} | {@code aggregate} | {@code table} */
            String answerType,
            CoverageDto coverage,
            List<String> caveats,
            boolean answerable,
            String reason
    ) {

        public static QueryResultDto answered(List<String> columns,
                                              List<Map<String, Object>> rows,
                                              String sql,
                                              String explanation,
                                              String headline,
                                              String summary,
                                              String answerType,
                                              CoverageDto coverage,
                                              List<String> caveats) {
            return new QueryResultDto(
                    columns,
                    rows,
                    rows.size(),
                    sql,
                    explanation,
                    headline,
                    summary,
                    answerType,
                    coverage,
                    caveats == null ? List.of() : List.copyOf(caveats),
                    true,
                    null);
        }

        public static QueryResultDto refused(String reason) {
            return new QueryResultDto(
                    List.of(), List.of(), 0, null, null, null, null, null, null, List.of(), false, reason);
        }
    }

    /**
     * How much of the answer rests on verified vs low-confidence extraction.
     * {@code verifiable} is false when the result set has no per-cell provenance
     * (e.g. a bare {@code SELECT SUM(...)} with no source rows).
     */
    public record CoverageDto(
            boolean verifiable,
            int rowCount,
            int includedRows,
            int excludedRows,
            int cellsWithValues,
            int lowConfidenceCells,
            /** Optional aggregate recomputed excluding low-confidence numeric cells. */
            Double aggregateIncludingLow,
            Double aggregateExcludingLow,
            String aggregateColumn
    ) {
        public static CoverageDto unverifiable(int rowCount) {
            return new CoverageDto(false, rowCount, rowCount, 0, 0, 0, null, null, null);
        }

        public static CoverageDto of(int rowCount, int includedRows, int excludedRows,
                                     int cellsWithValues, int lowConfidenceCells,
                                     Double aggregateIncludingLow, Double aggregateExcludingLow,
                                     String aggregateColumn) {
            return new CoverageDto(true, rowCount, includedRows, excludedRows,
                    cellsWithValues, lowConfidenceCells,
                    aggregateIncludingLow, aggregateExcludingLow, aggregateColumn);
        }
    }
}
