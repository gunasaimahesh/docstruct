package com.docstruct.dto;

import java.time.Instant;

import com.docstruct.domain.CollectionEntity;
import com.docstruct.domain.schema.DocumentSchema;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CollectionDto(
        String id,
        String name,
        String description,
        String documentType,
        DocumentSchema schema,
        int documentCount,
        int rowCount,
        Instant createdAt,
        Instant updatedAt
) {
    public static CollectionDto from(CollectionEntity entity) {
        return new CollectionDto(
                entity.getId(),
                entity.getName(),
                entity.getDescription(),
                entity.getDocumentType(),
                entity.getSchema(),
                entity.getDocumentCount(),
                entity.getRowCount(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
