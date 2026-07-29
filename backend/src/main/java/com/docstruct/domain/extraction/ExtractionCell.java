package com.docstruct.domain.extraction;

import com.docstruct.domain.ConfidenceLevel;
import com.docstruct.domain.ImportanceLevel;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A single extracted value with its extraction metadata.
 * {@code value} is a String/Double/Boolean/null for scalar columns,
 * or a {@code List<Map<String, ExtractionCell>>} for entity_array columns.
 *
 * {@code confidence} is the level AFTER server-side verification, not the level
 * the LLM claimed; {@code evidence} carries the citation, the numeric score and
 * the reason behind any downgrade.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExtractionCell(
        Object value,
        ConfidenceLevel confidence,
        ImportanceLevel importance,
        Boolean searchable,
        @JsonProperty("rawSource") String rawSource,
        CellEvidence evidence
) {
    public static ExtractionCell of(Object value, ConfidenceLevel confidence, ImportanceLevel importance) {
        return new ExtractionCell(value, confidence, importance, null, null, null);
    }

    /** Replaces the LLM-reported confidence with the verified level and its evidence. */
    public ExtractionCell verified(ConfidenceLevel verifiedConfidence, CellEvidence verifiedEvidence) {
        return new ExtractionCell(value, verifiedConfidence, importance, searchable, rawSource, verifiedEvidence);
    }

    /** Replaces the value, keeping all extraction metadata (used for nested entity rows). */
    public ExtractionCell withValue(Object newValue) {
        return new ExtractionCell(newValue, confidence, importance, searchable, rawSource, evidence);
    }
}
