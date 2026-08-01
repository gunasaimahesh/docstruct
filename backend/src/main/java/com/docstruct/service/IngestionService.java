package com.docstruct.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import com.docstruct.config.UploadProperties;
import com.docstruct.domain.CollectionEntity;
import com.docstruct.domain.ConfidenceLevel;
import com.docstruct.domain.DocumentEntity;
import com.docstruct.domain.DocumentFormat;
import com.docstruct.domain.extraction.DocumentAnalysis;
import com.docstruct.domain.extraction.ExtractionCell;
import com.docstruct.domain.extraction.ExtractionResult;
import com.docstruct.domain.extraction.SchemaMatchResult;
import com.docstruct.domain.schema.DocumentSchema;
import com.docstruct.domain.schema.SchemaColumn;
import com.docstruct.dto.CollectionDto;
import com.docstruct.dto.DocumentDto;
import com.docstruct.dto.UploadResponse;
import com.docstruct.exception.CollectionNotFoundException;
import com.docstruct.exception.ConcurrentSchemaUpdateException;
import com.docstruct.exception.FileTooLargeException;
import com.docstruct.exception.ParseException;
import com.docstruct.exception.ValidationException;
import com.docstruct.parser.ParseResult;
import com.docstruct.parser.ParserService;
import com.docstruct.repository.CollectionRepository;
import com.docstruct.repository.DocumentRepository;
import com.docstruct.repository.DynamicTableRepository;
import com.docstruct.util.ConfidenceCalculator;
import com.docstruct.util.ContentHash;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Orchestrates the upload workflow: parse the file, run the LLM extraction,
 * then persist the collection, document and data rows.
 *
 * The LLM call deliberately happens OUTSIDE the database transaction —
 * extraction can take many seconds and must not hold a connection open.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private static final int RAW_TEXT_LIMIT = 5000;

    /** How many times to replay the schema read-merge-write before giving up on a race. */
    static final int SCHEMA_MERGE_MAX_ATTEMPTS = 3;

    private final ParserService parserService;
    private final ExtractionService extractionService;
    private final CollectionRepository collectionRepository;
    private final DocumentRepository documentRepository;
    private final DynamicTableRepository dynamicTableRepository;
    private final TransactionTemplate transactionTemplate;
    private final UploadProperties uploadProperties;
    private final ObjectMapper objectMapper;

    public IngestionService(ParserService parserService,
                            ExtractionService extractionService,
                            CollectionRepository collectionRepository,
                            DocumentRepository documentRepository,
                            DynamicTableRepository dynamicTableRepository,
                            TransactionTemplate transactionTemplate,
                            UploadProperties uploadProperties,
                            ObjectMapper objectMapper) {
        this.parserService = parserService;
        this.extractionService = extractionService;
        this.collectionRepository = collectionRepository;
        this.documentRepository = documentRepository;
        this.dynamicTableRepository = dynamicTableRepository;
        this.transactionTemplate = transactionTemplate;
        this.uploadProperties = uploadProperties;
        this.objectMapper = objectMapper;
    }

    /** Uploads the first document of a NEW collection: infers schema, creates tables, extracts data. */
    public UploadResponse ingestNewCollection(MultipartFile file, String collectionName) {
        ParsedUpload upload = parseUpload(file);
        ParseResult parsed = upload.parsed();
        ExtractionResult extraction = extractionService.inferAndExtract(parsed, upload.contentHash());

        String name = collectionName != null && !collectionName.isBlank()
                ? collectionName
                : generateCollectionName(file.getOriginalFilename(), extraction.schema().documentType());

        // Derived from the verified per-cell confidences rather than from the schema
        // confidence the LLM reported about itself.
        ConfidenceLevel overallConfidence = ConfidenceCalculator.overall(extraction.rows());
        List<String> warnings = withVerificationWarning(extraction.warnings(), extraction.rows());

        return transactionTemplate.execute(tx -> {
            CollectionEntity collection = CollectionEntity.create(name, null, extraction.schema());
            collectionRepository.save(collection);

            DocumentEntity document = persistDocument(collection, file, parsed,
                    extraction.rows(), overallConfidence, warnings, extraction.analysis());

            dynamicTableRepository.createDataTables(collection.getId(), extraction.schema().columns());
            int inserted = dynamicTableRepository.insertRows(
                    collection.getId(), document.getId(),
                    extraction.schema().columns(), extraction.rows(), overallConfidence);

            collection.recordDocumentAdded(inserted);
            collectionRepository.save(collection);

            log.info("Created collection {} with {} rows from {}",
                    collection.getId(), inserted, file.getOriginalFilename());

            return buildResponse(collection, document, inserted, overallConfidence, warnings);
        });
    }

    /** Uploads an additional document into an EXISTING collection, evolving its schema when needed. */
    public UploadResponse ingestIntoCollection(String collectionId, MultipartFile file) {
        CollectionEntity existing = collectionRepository.findById(collectionId)
                .orElseThrow(() -> new CollectionNotFoundException(collectionId));

        ParsedUpload upload = parseUpload(file);
        ParseResult parsed = upload.parsed();
        SchemaMatchResult match = extractionService.extractWithSchema(
                parsed, existing.getSchema(), upload.contentHash());

        ConfidenceLevel overallConfidence = ConfidenceCalculator.overall(match.rows());

        // A concurrent upload can evolve the schema during the seconds this document
        // spent in the LLM call. The optimistic lock on the collection turns that into
        // a failed commit rather than a silently dropped column, so the read-merge-write
        // is replayed against whatever schema the winner left behind. The LLM result is
        // reused as-is: only the merge needs redoing, not the extraction.
        for (int attempt = 1; attempt <= SCHEMA_MERGE_MAX_ATTEMPTS; attempt++) {
            try {
                return transactionTemplate.execute(tx ->
                        writeIntoCollection(collectionId, file, parsed, match, overallConfidence));
            } catch (OptimisticLockingFailureException e) {
                log.warn("Schema merge conflict on collection {} (attempt {} of {})",
                        collectionId, attempt, SCHEMA_MERGE_MAX_ATTEMPTS);
            }
        }

        throw new ConcurrentSchemaUpdateException(collectionId, SCHEMA_MERGE_MAX_ATTEMPTS);
    }

    // ---- Steps ----

    /**
     * One read-merge-write attempt, run inside a transaction. Replaying this is safe
     * because a rolled-back attempt leaves nothing behind: the document row and data
     * rows go with the transaction, and the {@code ADD COLUMN IF NOT EXISTS} half of
     * schema evolution is idempotent.
     */
    private UploadResponse writeIntoCollection(String collectionId, MultipartFile file, ParseResult parsed,
                                               SchemaMatchResult match, ConfidenceLevel overallConfidence) {
        CollectionEntity collection = collectionRepository.findById(collectionId)
                .orElseThrow(() -> new CollectionNotFoundException(collectionId));

        // Rebuilt per attempt: evolveSchema appends to this list, and a retry must not
        // inherit the warnings of the attempt that was rolled back.
        List<String> warnings = withVerificationWarning(match.warnings(), match.rows());

        evolveSchema(collection, match.newColumns(), warnings);

        DocumentEntity document = persistDocument(collection, file, parsed,
                match.rows(), overallConfidence, warnings, match.analysis());

        int inserted = dynamicTableRepository.insertRows(
                collection.getId(), document.getId(),
                collection.getSchema().columns(), match.rows(), overallConfidence);

        collection.recordDocumentAdded(inserted);
        collectionRepository.save(collection);

        log.info("Added document to collection {}: {} rows from {}",
                collection.getId(), inserted, file.getOriginalFilename());

        return buildResponse(collection, document, inserted, overallConfidence, warnings);
    }

    /** A parsed document alongside the digest of the bytes it was parsed from. */
    private record ParsedUpload(ParseResult parsed, String contentHash) {
    }

    private ParsedUpload parseUpload(MultipartFile file) {
        if (file == null || file.getOriginalFilename() == null) {
            throw new ValidationException("No file provided");
        }
        if (file.getSize() > uploadProperties.maxFileSizeBytes()) {
            throw new FileTooLargeException(file.getSize(), uploadProperties.maxFileSizeBytes());
        }
        if (file.isEmpty()) {
            throw new ParseException("File is empty", "The uploaded file contains no data.");
        }

        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read uploaded file", e);
        }

        // Hashed before parsing: the raw bytes are what identify the document,
        // and the parse is a deterministic function of them.
        String contentHash = ContentHash.sha256(content);

        log.info("Processing upload: filename={}, size={}, mimeType={}, sha256={}",
                file.getOriginalFilename(), file.getSize(), file.getContentType(), contentHash);

        return new ParsedUpload(
                parserService.parse(content, file.getOriginalFilename(), file.getContentType()),
                contentHash);
    }

    /**
     * Tells the user up front how many values need review, instead of leaving them
     * to discover the low-confidence fields by clicking through the table.
     */
    private List<String> withVerificationWarning(List<String> warnings,
                                                 List<Map<String, ExtractionCell>> rows) {
        List<String> combined = new ArrayList<>(warnings);
        int lowConfidence = ConfidenceCalculator.lowConfidenceValueCount(rows);
        if (lowConfidence > 0) {
            combined.add(("%d extracted value(s) could not be fully verified against the document "
                    + "and are marked low confidence").formatted(lowConfidence));
        }
        return combined;
    }

    /** Adds newly detected columns to the schema and the data table (schema evolution). */
    private void evolveSchema(CollectionEntity collection, List<SchemaColumn> newColumns, List<String> warnings) {
        if (newColumns.isEmpty()) {
            return;
        }

        List<SchemaColumn> columns = new ArrayList<>(collection.getSchema().columns());
        int added = 0;
        for (SchemaColumn newCol : newColumns) {
            boolean exists = columns.stream()
                    .anyMatch(c -> c.name().equalsIgnoreCase(newCol.name()));
            if (!exists) {
                columns.add(newCol);
                dynamicTableRepository.addColumn(collection.getId(), newCol);
                added++;
            }
        }

        if (added > 0) {
            collection.updateSchema(collection.getSchema().withColumns(columns));
            warnings.add("Schema evolved: %d new column(s) detected".formatted(added));
        }
    }

    private DocumentEntity persistDocument(CollectionEntity collection, MultipartFile file, ParseResult parsed,
                                           List<Map<String, ExtractionCell>> rows, ConfidenceLevel confidence,
                                           List<String> warnings, DocumentAnalysis analysis) {
        String rawText = parsed.format() == DocumentFormat.IMAGE
                ? ""
                : parsed.text().substring(0, Math.min(parsed.text().length(), RAW_TEXT_LIMIT));

        List<Map<String, Object>> rawJson = objectMapper.convertValue(rows, new TypeReference<>() {
        });

        DocumentEntity document = DocumentEntity.create(
                collection.getId(),
                file.getOriginalFilename(),
                parsed.format(),
                file.getSize(),
                rows.size(),
                confidence,
                warnings,
                rawText,
                rawJson);

        if (analysis != null) {
            document.applyAnalysis(analysis);
        }

        // Flush immediately: the JdbcTemplate row inserts that follow reference
        // this document via foreign key within the same transaction.
        return documentRepository.saveAndFlush(document);
    }

    private UploadResponse buildResponse(CollectionEntity collection, DocumentEntity document,
                                         int insertedRows, ConfidenceLevel confidence, List<String> warnings) {
        return new UploadResponse(
                true,
                CollectionDto.from(collection),
                DocumentDto.from(document),
                new UploadResponse.ExtractionSummary(insertedRows, confidence, warnings, collection.getSchema()));
    }

    /** Generates a readable collection name from the file name and detected document type. */
    static String generateCollectionName(String filename, String documentType) {
        String baseName = (filename != null ? filename : "document")
                .replaceAll("\\.[^.]+$", "")
                .replaceAll("[_-]", " ");
        String type = documentType.replace("_", " ");

        if (baseName.toLowerCase().contains(type.toLowerCase())) {
            return baseName;
        }
        return type + " — " + baseName;
    }
}
