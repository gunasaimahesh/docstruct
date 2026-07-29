package com.docstruct.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.docstruct.domain.ConfidenceLevel;
import com.docstruct.domain.DocumentEntity;
import com.docstruct.domain.DocumentFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DocumentDto(
        String id,
        String collectionId,
        String filename,
        DocumentFormat format,
        long size,
        int rowCount,
        ConfidenceLevel confidence,
        List<String> warnings,
        String purpose,
        String owner,
        String audience,
        List<String> sections,
        @JsonProperty("ai_summary") String aiSummary,
        List<Map<String, Object>> rawJson,
        Instant createdAt
) {
    public static DocumentDto from(DocumentEntity entity) {
        return new DocumentDto(
                entity.getId(),
                entity.getCollectionId(),
                entity.getFilename(),
                entity.getFormat(),
                entity.getSizeBytes(),
                entity.getRowCount(),
                entity.getConfidence(),
                entity.getWarnings(),
                entity.getPurpose(),
                entity.getOwner(),
                entity.getAudience(),
                entity.getSections(),
                entity.getAiSummary(),
                entity.getRawJson(),
                entity.getCreatedAt());
    }
}
