package com.docstruct.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * Deterministic filter/sort request — no natural language, no LLM.
 * Columns and operators are validated against the collection schema server-side.
 *
 * <p>When {@link FilterCondition#entity()} is set, the condition targets a nested
 * {@code entity_array} child table and is applied via a correlated {@code EXISTS}
 * (any matching child row qualifies the parent).
 */
public record FilterRequest(
        @Valid List<FilterCondition> filters,
        /** {@code all} (AND) or {@code any} (OR). Defaults to all. */
        String match,
        @Valid SortSpec sort,
        Integer page,
        Integer limit,
        /** When true, supporting rows with any low-confidence value cell are dropped. */
        Boolean excludeLowConfidence
) {
    /** Back-compat for callers that omit {@code excludeLowConfidence}. */
    public FilterRequest(List<FilterCondition> filters, String match, SortSpec sort,
                         Integer page, Integer limit) {
        this(filters, match, sort, page, limit, null);
    }

    public boolean excludeLowConfidenceOrFalse() {
        return Boolean.TRUE.equals(excludeLowConfidence);
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
