package com.docstruct.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.docstruct.domain.extraction.DocumentChunk;
import com.docstruct.domain.extraction.ExtractionResult;
import com.docstruct.domain.extraction.SchemaMatchResult;
import com.docstruct.domain.schema.DocumentSchema;
import com.docstruct.exception.ExtractionException;
import com.docstruct.llm.ExtractionResponseMapper;
import com.docstruct.llm.LlmClient;
import com.docstruct.llm.PromptTemplates;
import com.docstruct.parser.ParseResult;
import com.docstruct.util.ConfidenceScorer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The core AI engine: turns parsed documents into structured data,
 * either by inferring a fresh schema or by matching an existing one.
 *
 * The document is never handed to the LLM as an anonymous blob. It goes in as
 * numbered, page-tagged chunks so that every extracted value can cite its source
 * and the citation can be verified afterwards by a {@link ConfidenceScorer}
 * built from the very same chunks.
 */
@Service
public class ExtractionService {

    private static final Logger log = LoggerFactory.getLogger(ExtractionService.class);

    private static final String IMAGE_CONTEXT = """
            This document is an image with no text layer. Read it visually from the attached image.
            There are no chunks to cite.""";

    private static final String IMAGE_UNVERIFIABLE_REASON =
            "Image document — the value could not be verified against extracted text";

    private static final int EXTRACTION_MAX_TOKENS = 8192;

    private final LlmClient llmClient;
    private final ExtractionResponseMapper mapper;
    private final ObjectMapper objectMapper;

    public ExtractionService(LlmClient llmClient, ExtractionResponseMapper mapper, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    /** Infers a schema and extracts data — used for the FIRST document in a collection. */
    public ExtractionResult inferAndExtract(ParseResult parsed) {
        String prompt = PromptTemplates.schemaInference(contextFor(parsed));

        log.info("Starting schema inference (image={}, textLength={}, chunks={})",
                parsed.isImage(), parsed.text().length(), parsed.chunks().size());

        JsonNode response = llmClient.callJson(prompt, imageDataFor(parsed), EXTRACTION_MAX_TOKENS);
        ExtractionResult result = mapper.toExtractionResult(response, scorerFor(parsed));

        log.info("Schema inference complete: type={}, columns={}, rows={}, confidence={}",
                result.schema().documentType(), result.schema().columns().size(),
                result.rowCount(), result.schema().confidence());

        return result;
    }

    /** Extracts data against an existing schema — used for subsequent documents. */
    public SchemaMatchResult extractWithSchema(ParseResult parsed, DocumentSchema existingSchema) {
        String prompt = PromptTemplates.schemaMatching(
                contextFor(parsed), schemaAsJson(existingSchema), existingSchema.documentType());

        log.info("Extracting with existing schema (type={}, columns={}, image={}, chunks={})",
                existingSchema.documentType(), existingSchema.columns().size(),
                parsed.isImage(), parsed.chunks().size());

        JsonNode response = llmClient.callJson(prompt, imageDataFor(parsed), EXTRACTION_MAX_TOKENS);
        return mapper.toSchemaMatchResult(response, existingSchema.columns(), scorerFor(parsed));
    }

    /**
     * Renders the document as addressable chunks:
     * {@code [chunk 3 | page 1]} followed by that chunk's exact text.
     * The full document is still supplied — the numbering exists to make every
     * value traceable, not to hide parts of the document from the model.
     */
    private String contextFor(ParseResult parsed) {
        if (parsed.isImage()) {
            return IMAGE_CONTEXT;
        }
        if (parsed.text().isBlank() || parsed.chunks().isEmpty()) {
            throw new ExtractionException(
                    "Document appears to be empty. No text could be extracted.",
                    "The document may be a scanned image without embedded text, or it may be corrupted.");
        }

        StringBuilder context = new StringBuilder();
        for (DocumentChunk chunk : parsed.chunks()) {
            context.append("[chunk ").append(chunk.index())
                    .append(" | page ").append(chunk.page()).append("]\n")
                    .append(chunk.text()).append("\n\n");
        }
        return context.toString().stripTrailing();
    }

    /** Images have no source text, so their values cannot be verified — and are not pretended to be. */
    private ConfidenceScorer scorerFor(ParseResult parsed) {
        return parsed.isImage()
                ? ConfidenceScorer.unverifiable(IMAGE_UNVERIFIABLE_REASON)
                : ConfidenceScorer.forChunks(parsed.chunks());
    }

    private LlmClient.ImageData imageDataFor(ParseResult parsed) {
        return parsed.isImage() ? new LlmClient.ImageData(parsed.imageMimeType(), parsed.imageBase64()) : null;
    }

    private String schemaAsJson(DocumentSchema schema) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(schema);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize schema", e);
        }
    }
}
