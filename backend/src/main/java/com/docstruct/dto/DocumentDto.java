package com.docstruct.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.docstruct.domain.ConfidenceLevel;
import com.docstruct.domain.DocumentEntity;
import com.docstruct.domain.DocumentFormat;
import com.docstruct.domain.extraction.DocumentTypeInfo;
import com.docstruct.domain.extraction.KnowledgeSection;
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
        DocumentTypeInfo documentType,
        List<KnowledgeSection> knowledgeSections,
        @JsonProperty("ai_summary") String aiSummary,
        List<Map<String, Object>> rawJson,
        /** True when original file bytes are available via the /original endpoint. */
        boolean hasOriginal,
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
                documentTypeOf(entity),
                // A document whose fields have no meaningful grouping still reports its type,
                // so the client can say so instead of showing an empty page.
                entity.getKnowledgeSections() == null ? List.of() : entity.getKnowledgeSections(),
                entity.getAiSummary(),
                entity.getRawJson(),
                entity.getHasOriginal(),
                entity.getCreatedAt());
    }

    private static DocumentTypeInfo documentTypeOf(DocumentEntity entity) {
        return entity.getDocumentTypeName() == null
                ? null
                : new DocumentTypeInfo(entity.getDocumentTypeName(), entity.getDocumentTypeCategory());
    }
}
