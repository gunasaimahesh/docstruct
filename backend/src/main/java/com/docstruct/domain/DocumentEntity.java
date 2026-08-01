package com.docstruct.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.docstruct.domain.extraction.DocumentAnalysis;
import com.docstruct.domain.extraction.KnowledgeSection;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/** Metadata about a single ingested document. */
@Entity
@Table(name = "documents", indexes = @Index(name = "idx_documents_collection", columnList = "collection_id"))
public class DocumentEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "collection_id", nullable = false, length = 36)
    private String collectionId;

    @Column(nullable = false)
    private String filename;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentFormat format;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "row_count", nullable = false)
    private int rowCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ConfidenceLevel confidence;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "warnings_json", columnDefinition = "jsonb")
    private List<String> warnings;

    private String purpose;

    private String owner;

    private String audience;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sections_json", columnDefinition = "jsonb")
    private List<String> sections;

    /** What a reader calls this document, e.g. "Income Tax Return" in the "Financial" family. */
    @Column(name = "document_type_name")
    private String documentTypeName;

    @Column(name = "document_type_category")
    private String documentTypeCategory;

    /**
     * How this document's fields group for reading. Held per document rather than per
     * collection: the schema is shared, but a reader meets each document on its own terms.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "knowledge_sections_json", columnDefinition = "jsonb")
    private List<KnowledgeSection> knowledgeSections;

    @Column(name = "ai_summary", columnDefinition = "text")
    private String aiSummary;

    @Column(name = "raw_text", columnDefinition = "text")
    private String rawText;

    /** The full hierarchical extraction rows, kept for the document viewer. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_json", columnDefinition = "jsonb")
    private List<Map<String, Object>> rawJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected DocumentEntity() {
        // for JPA
    }

    public static DocumentEntity create(
            String collectionId,
            String filename,
            DocumentFormat format,
            long sizeBytes,
            int rowCount,
            ConfidenceLevel confidence,
            List<String> warnings,
            String rawText,
            List<Map<String, Object>> rawJson
    ) {
        DocumentEntity entity = new DocumentEntity();
        entity.id = UUID.randomUUID().toString();
        entity.collectionId = collectionId;
        entity.filename = filename;
        entity.format = format;
        entity.sizeBytes = sizeBytes;
        entity.rowCount = rowCount;
        entity.confidence = confidence;
        entity.warnings = warnings;
        entity.rawText = rawText;
        entity.rawJson = rawJson;
        entity.createdAt = Instant.now();
        return entity;
    }

    public void applyAnalysis(DocumentAnalysis analysis) {
        this.purpose = analysis.purpose();
        this.owner = analysis.owner();
        this.audience = analysis.audience();
        this.sections = analysis.detectedSections();
        this.aiSummary = analysis.aiSummary();
        this.knowledgeSections = analysis.knowledgeSections();
        if (analysis.documentType() != null) {
            this.documentTypeName = analysis.documentType().name();
            this.documentTypeCategory = analysis.documentType().category();
        }
    }

    public String getId() {
        return id;
    }

    public String getCollectionId() {
        return collectionId;
    }

    public String getFilename() {
        return filename;
    }

    public DocumentFormat getFormat() {
        return format;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public int getRowCount() {
        return rowCount;
    }

    public ConfidenceLevel getConfidence() {
        return confidence;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public String getPurpose() {
        return purpose;
    }

    public String getOwner() {
        return owner;
    }

    public String getAudience() {
        return audience;
    }

    public List<String> getSections() {
        return sections;
    }

    public String getDocumentTypeName() {
        return documentTypeName;
    }

    public String getDocumentTypeCategory() {
        return documentTypeCategory;
    }

    public List<KnowledgeSection> getKnowledgeSections() {
        return knowledgeSections;
    }

    public String getAiSummary() {
        return aiSummary;
    }

    public String getRawText() {
        return rawText;
    }

    public List<Map<String, Object>> getRawJson() {
        return rawJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
