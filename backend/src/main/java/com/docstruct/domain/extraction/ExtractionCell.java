package com.docstruct.domain.extraction;

import com.docstruct.domain.ConfidenceLevel;
import com.docstruct.domain.ImportanceLevel;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A single extracted value with its extraction metadata.
 * {@code value} is a String/Double/Boolean/null for scalar columns,
 * or a {@code List<Map<String, ExtractionCell>>} for entity_array columns.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExtractionCell(
        Object value,
        ConfidenceLevel confidence,
        ImportanceLevel importance,
        Boolean searchable,
        @JsonProperty("rawSource") String rawSource
) {
    public static ExtractionCell of(Object value, ConfidenceLevel confidence, ImportanceLevel importance) {
        return new ExtractionCell(value, confidence, importance, null, null);
    }
}
