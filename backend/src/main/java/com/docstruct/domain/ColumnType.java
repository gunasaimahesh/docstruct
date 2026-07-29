package com.docstruct.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Inferred column data types. Serialized as lowercase (e.g. "entity_array"). */
public enum ColumnType {
    TEXT, NUMBER, DATE, CURRENCY, BOOLEAN, EMAIL, URL, ENTITY_ARRAY;

    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }

    /** Lenient parsing — unknown types fall back to TEXT, mirroring the original behavior. */
    @JsonCreator
    public static ColumnType fromJson(String value) {
        if (value == null) {
            return TEXT;
        }
        try {
            return valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return TEXT;
        }
    }
}
