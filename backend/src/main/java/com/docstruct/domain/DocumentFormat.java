package com.docstruct.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Supported document input formats. */
public enum DocumentFormat {
    PDF, IMAGE, CSV, TEXT;

    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }

    @JsonCreator
    public static DocumentFormat fromJson(String value) {
        return valueOf(value.toUpperCase());
    }
}
