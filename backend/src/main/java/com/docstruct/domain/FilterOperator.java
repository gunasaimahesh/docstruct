package com.docstruct.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Operators the structured (no-LLM) filter path accepts. Each carries whether a
 * value is required — {@code is_empty} / {@code is_not_empty} bind none.
 */
public enum FilterOperator {
    EQ(true),
    NEQ(true),
    CONTAINS(true),
    STARTS_WITH(true),
    ENDS_WITH(true),
    GT(true),
    GTE(true),
    LT(true),
    LTE(true),
    IS_EMPTY(false),
    IS_NOT_EMPTY(false);

    private final boolean requiresValue;

    FilterOperator(boolean requiresValue) {
        this.requiresValue = requiresValue;
    }

    public boolean requiresValue() {
        return requiresValue;
    }

    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }

    @JsonCreator
    public static FilterOperator fromJson(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("operator is required");
        }
        return valueOf(value.trim().toUpperCase());
    }
}
