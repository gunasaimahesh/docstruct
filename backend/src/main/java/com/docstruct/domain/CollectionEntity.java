package com.docstruct.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.docstruct.domain.schema.DocumentSchema;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** A collection of similar documents sharing one inferred schema. */
@Entity
@Table(name = "collections")
public class CollectionEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(name = "document_type", nullable = false)
    private String documentType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "schema_json", nullable = false, columnDefinition = "jsonb")
    private DocumentSchema schema;

    @Column(name = "document_count", nullable = false)
    private int documentCount;

    @Column(name = "row_count", nullable = false)
    private int rowCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Optimistic lock guard. Ingestion read-modify-writes the schema JSON and both
     * counters, so two uploads racing into the same collection would otherwise
     * silently overwrite each other's changes. The column default backfills rows
     * that predate this field, since the fixed tables are managed by ddl-auto.
     */
    @Version
    @ColumnDefault("0")
    @Column(name = "version", nullable = false)
    private long version;

    protected CollectionEntity() {
        // for JPA
    }

    public static CollectionEntity create(String name, String description, DocumentSchema schema) {
        CollectionEntity entity = new CollectionEntity();
        entity.id = UUID.randomUUID().toString();
        entity.name = name;
        entity.description = description;
        entity.documentType = schema.documentType();
        entity.schema = schema;
        entity.documentCount = 0;
        entity.rowCount = 0;
        entity.createdAt = Instant.now();
        entity.updatedAt = Instant.now();
        return entity;
    }

    public void updateSchema(DocumentSchema schema) {
        this.schema = schema;
        this.documentType = schema.documentType();
        this.updatedAt = Instant.now();
    }

    public void recordDocumentAdded(int rowsAdded) {
        this.documentCount++;
        this.rowCount += rowsAdded;
        this.updatedAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getDocumentType() {
        return documentType;
    }

    public DocumentSchema getSchema() {
        return schema;
    }

    public int getDocumentCount() {
        return documentCount;
    }

    public int getRowCount() {
        return rowCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
