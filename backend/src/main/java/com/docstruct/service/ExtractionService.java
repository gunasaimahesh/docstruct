package com.docstruct.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.docstruct.domain.extraction.ExtractionResult;
import com.docstruct.domain.extraction.SchemaMatchResult;
import com.docstruct.domain.schema.DocumentSchema;
import com.docstruct.exception.ExtractionException;
import com.docstruct.llm.ExtractionResponseMapper;
import com.docstruct.llm.LlmClient;
import com.docstruct.llm.PromptTemplates;
import com.docstruct.parser.ParseResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The core AI engine: turns parsed documents into structured data,
 * either by inferring a fresh schema or by matching an existing one.
 */
@Service
public class ExtractionService {

    private static final Logger log = LoggerFactory.getLogger(ExtractionService.class);

    private static final String IMAGE_PLACEHOLDER = "[Image document — see attached image]";
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
        String prompt = PromptTemplates.schemaInference(documentTextFor(parsed));

        log.info("Starting schema inference (image={}, textLength={})",
                parsed.isImage(), parsed.text().length());

        JsonNode response = llmClient.callJson(prompt, imageDataFor(parsed), EXTRACTION_MAX_TOKENS);
        ExtractionResult result = mapper.toExtractionResult(response);

        log.info("Schema inference complete: type={}, columns={}, rows={}, confidence={}",
                result.schema().documentType(), result.schema().columns().size(),
                result.rowCount(), result.schema().confidence());

        return result;
    }

    /** Extracts data against an existing schema — used for subsequent documents. */
    public SchemaMatchResult extractWithSchema(ParseResult parsed, DocumentSchema existingSchema) {
        String prompt = PromptTemplates.schemaMatching(
                documentTextFor(parsed), schemaAsJson(existingSchema), existingSchema.documentType());

        log.info("Extracting with existing schema (type={}, columns={}, image={})",
                existingSchema.documentType(), existingSchema.columns().size(), parsed.isImage());

        JsonNode response = llmClient.callJson(prompt, imageDataFor(parsed), EXTRACTION_MAX_TOKENS);
        return mapper.toSchemaMatchResult(response, existingSchema.columns());
    }

    private String documentTextFor(ParseResult parsed) {
        if (parsed.isImage()) {
            return IMAGE_PLACEHOLDER;
        }
        if (parsed.text().isBlank()) {
            throw new ExtractionException(
                    "Document appears to be empty. No text could be extracted.",
                    "The document may be a scanned image without embedded text, or it may be corrupted.");
        }
        return parsed.text();
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
