package com.docstruct.dto;

import java.util.List;

import com.docstruct.domain.ConfidenceLevel;
import com.docstruct.domain.schema.DocumentSchema;

public record UploadResponse(
        boolean success,
        CollectionDto collection,
        DocumentDto document,
        ExtractionSummary extraction
) {
    public record ExtractionSummary(
            int rowCount,
            ConfidenceLevel confidence,
            List<String> warnings,
            DocumentSchema schema
    ) {
    }
}
