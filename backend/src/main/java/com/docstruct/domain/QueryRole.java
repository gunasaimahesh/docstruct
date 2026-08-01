package com.docstruct.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Semantic role of a schema column for query UX. Storage/coercion still uses
 * {@link ColumnType}; this only guides operators, widgets and placeholders.
 */
public enum QueryRole {
    STATUS,
    PERSON_NAME,
    COMPANY,
    ORGANIZATION,
    MONEY,
    CURRENCY,
    PERCENTAGE,
    DATE,
    PHONE,
    EMAIL,
    URL,
    COUNTRY,
    CITY,
    IDENTIFIER,
    DESCRIPTION,
    BOOLEAN,
    NUMBER;

    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }

    /** Lenient — unknown roles become null so the UI falls back to column type. */
    @JsonCreator
    public static QueryRole fromJson(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Low-cardinality roles whose filter values should be loaded via SELECT DISTINCT. */
    public boolean isCategorical() {
        return this == STATUS || this == COUNTRY || this == CITY || this == CURRENCY;
    }
}
