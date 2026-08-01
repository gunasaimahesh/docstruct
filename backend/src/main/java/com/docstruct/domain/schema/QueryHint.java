package com.docstruct.domain.schema;

import com.docstruct.domain.QueryRole;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * LLM-inferred query semantics for a schema column. Populated once at extraction;
 * never carries enumerated values — those come from the database at query time.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record QueryHint(
        Boolean filterable,
        Boolean sortable,
        Boolean groupable,
        QueryRole role,
        String unit,
        String example
) {
    /** True unless the model (or description role) says otherwise. */
    public boolean isFilterable() {
        if (filterable != null) {
            return filterable;
        }
        return role != QueryRole.DESCRIPTION;
    }

    /** True unless the model says otherwise. */
    public boolean isSortable() {
        return sortable == null || sortable;
    }
}
