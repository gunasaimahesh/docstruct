package com.docstruct.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Importance level of an extracted field. Serialized as lowercase. */
public enum ImportanceLevel {
    HIGH, MEDIUM, LOW;

    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }

    @JsonCreator
    public static ImportanceLevel fromJson(String value) {
        if (value == null) {
            return MEDIUM;
        }
        return switch (value.toLowerCase()) {
            case "high" -> HIGH;
            case "low" -> LOW;
            default -> MEDIUM;
        };
    }
}
