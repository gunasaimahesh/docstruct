package com.docstruct.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * Deterministic filter/sort request — no natural language, no LLM.
 * Columns and operators are validated against the collection schema server-side.
 *
 * <p>When {@link FilterCondition#entity()} is set, the condition targets a nested
 * {@code entity_array}. By default the unit of retrieval follows that level —
 * matching <em>entries</em> (child rows) are returned. Pass
 * {@code resultUnit=documents} to keep the older document-centric EXISTS path.
 */
public record FilterRequest(
        @Valid List<FilterCondition> filters,
        /** {@code all} (AND) or {@code any} (OR). Defaults to all. */
        String match,
        @Valid SortSpec sort,
        Integer page,
        Integer limit,
        /** When true, supporting rows with any low-confidence value cell are dropped. */
        Boolean excludeLowConfidence,
        /**
         * {@code entries} or {@code documents}. Null = default: entries when the
         * filter targets a single nested entity, otherwise documents.
         */
        String resultUnit
) {
    /** Back-compat for callers that omit confidence / result-unit options. */
    public FilterRequest(List<FilterCondition> filters, String match, SortSpec sort,
                         Integer page, Integer limit) {
        this(filters, match, sort, page, limit, null, null);
    }

    public FilterRequest(List<FilterCondition> filters, String match, SortSpec sort,
                         Integer page, Integer limit, Boolean excludeLowConfidence) {
        this(filters, match, sort, page, limit, excludeLowConfidence, null);
    }

    public boolean excludeLowConfidenceOrFalse() {
        return Boolean.TRUE.equals(excludeLowConfidence);
    }

    /** Normalized unit, or null when the caller left the default. */
    public String resultUnitOrNull() {
        if (resultUnit == null || resultUnit.isBlank()) {
            return null;
        }
        String normalized = resultUnit.trim().toLowerCase();
        if ("entries".equals(normalized) || "documents".equals(normalized)) {
            return normalized;
        }
        return null;
    }
    public record FilterCondition(
            @NotBlank(message = "column is required") String column,
            @NotBlank(message = "operator is required") String operator,
            Object value,
            /**
             * Optional top-level {@code entity_array} column name (or its
             * {@code entitySchema.name}). Null/blank = main data table.
             */
            String entity
    ) {
        /** Back-compat for callers that omit {@code entity}. */
        public FilterCondition(String column, String operator, Object value) {
            this(column, operator, value, null);
        }
    }

    public record SortSpec(
            @NotBlank(message = "sort column is required") String column,
            /** {@code asc} or {@code desc}. Defaults to asc. */
            String direction
    ) {
    }

    public List<FilterCondition> filtersOrEmpty() {
        return filters == null ? List.of() : filters;
    }
}
