package com.docstruct.domain.extraction;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Semantic understanding of a document, produced during schema inference. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DocumentAnalysis(
        String purpose,
        String owner,
        String audience,
        @JsonProperty("useful_data_identified") String usefulDataIdentified,
        @JsonProperty("detected_sections") List<String> detectedSections,
        @JsonProperty("ai_summary") String aiSummary
) {
}
