package com.docstruct.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Confidence level for an extraction. Serialized as lowercase to match the API contract. */
public enum ConfidenceLevel {
    HIGH, MEDIUM, LOW;

    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }

    /** Lenient parsing — unknown values fall back to MEDIUM, mirroring the original behavior. */
    @JsonCreator
    public static ConfidenceLevel fromJson(String value) {
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
