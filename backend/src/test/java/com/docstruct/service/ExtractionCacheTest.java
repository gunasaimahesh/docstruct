package com.docstruct.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.docstruct.config.ExtractionCacheProperties;
import com.docstruct.domain.ColumnType;
import com.docstruct.domain.ConfidenceLevel;
import com.docstruct.domain.DocumentFormat;
import com.docstruct.domain.extraction.ExtractionResult;
import com.docstruct.domain.extraction.SchemaMatchResult;
import com.docstruct.domain.schema.DocumentSchema;
import com.docstruct.domain.schema.SchemaColumn;
import com.docstruct.exception.AiServiceException;
import com.docstruct.llm.ExtractionResponseMapper;
import com.docstruct.llm.LlmClient;
import com.docstruct.parser.ParseResult;
import com.docstruct.util.ContentHash;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Re-uploading the same bytes must not pay for the same extraction twice.
 * The LLM client is mocked, so "how many calls" is the whole assertion.
 */
@ExtendWith(MockitoExtension.class)
class ExtractionCacheTest {

    private static final String DOCUMENT = "Vendor\nAcme Corp";
    private static final String HASH = ContentHash.sha256(DOCUMENT);
    private static final String OTHER_HASH = ContentHash.sha256("Vendor\nGlobex");

    private static final String INFERENCE_RESPONSE = """
            {
              "document_type": "invoice",
              "schema": {"columns": [{"name": "Vendor", "type": "text", "required": true}],
                         "confidence": "high"},
              "rows": [{"Vendor": {"value": "Acme Corp", "page": 1, "chunk": 1,
                                   "confidence": "high", "raw_source": "Acme Corp"}}]
            }
            """;

    private static final String MATCH_RESPONSE = """
            {
              "rows": [{"Vendor": {"value": "Acme Corp", "page": 1, "chunk": 1,
                                   "confidence": "high", "raw_source": "Acme Corp"}}],
              "new_columns": []
            }
            """;

    private static final DocumentSchema SCHEMA = new DocumentSchema(
            List.of(new SchemaColumn("Vendor", ColumnType.TEXT, null, true)),
            "invoice", ConfidenceLevel.HIGH);

    @Mock
    private LlmClient llmClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ExtractionService extractionService;

    @BeforeEach
    void setUp() {
        extractionService = extractionServiceWith(cacheEnabled(true));
    }

    private ExtractionService extractionServiceWith(ExtractionCache cache) {
        return new ExtractionService(llmClient, new ExtractionResponseMapper(), objectMapper, cache);
    }

    private static ExtractionCache cacheEnabled(boolean enabled) {
        return new ExtractionCache(
                new ExtractionCacheProperties(enabled, 100, Duration.ofHours(1)));
    }

    private static ParseResult parsed() {
        return ParseResult.ofText(DOCUMENT, DocumentFormat.CSV, Map.of());
    }

    private JsonNode json(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void llmReturns(String response) {
        when(llmClient.callJson(anyString(), any(), anyInt())).thenReturn(json(response));
    }

    // ---- Schema inference ----

    @Test
    void reUploadingIdenticalBytesReusesTheExtraction() {
        llmReturns(INFERENCE_RESPONSE);

        ExtractionResult first = extractionService.inferAndExtract(parsed(), HASH);
        ExtractionResult second = extractionService.inferAndExtract(parsed(), HASH);

        verify(llmClient, times(1)).callJson(anyString(), any(), anyInt());
        assertThat(second).isSameAs(first);
        assertThat(second.rows().get(0).get("Vendor").value()).isEqualTo("Acme Corp");
    }

    @Test
    void aDifferentDocumentIsExtractedAgain() {
        llmReturns(INFERENCE_RESPONSE);

        extractionService.inferAndExtract(parsed(), HASH);
        extractionService.inferAndExtract(parsed(), OTHER_HASH);

        verify(llmClient, times(2)).callJson(anyString(), any(), anyInt());
    }

    @Test
    void aFailedExtractionIsNotCached() {
        when(llmClient.callJson(anyString(), any(), anyInt()))
                .thenThrow(new AiServiceException("provider out of credits"))
                .thenReturn(json(INFERENCE_RESPONSE));

        assertThatThrownBy(() -> extractionService.inferAndExtract(parsed(), HASH))
                .isInstanceOf(AiServiceException.class);

        // Caching the failure would turn one bad response into a permanently broken document.
        assertThat(extractionService.inferAndExtract(parsed(), HASH).rowCount()).isEqualTo(1);
        verify(llmClient, times(2)).callJson(anyString(), any(), anyInt());
    }

    @Test
    void disablingTheCacheExtractsEveryTime() {
        llmReturns(INFERENCE_RESPONSE);
        extractionService = extractionServiceWith(cacheEnabled(false));

        extractionService.inferAndExtract(parsed(), HASH);
        extractionService.inferAndExtract(parsed(), HASH);

        verify(llmClient, times(2)).callJson(anyString(), any(), anyInt());
    }

    // ---- Schema matching ----

    @Test
    void theSameDocumentMatchedAgainstTheSameSchemaIsReused() {
        llmReturns(MATCH_RESPONSE);

        SchemaMatchResult first = extractionService.extractWithSchema(parsed(), SCHEMA, HASH);
        SchemaMatchResult second = extractionService.extractWithSchema(parsed(), SCHEMA, HASH);

        verify(llmClient, times(1)).callJson(anyString(), any(), anyInt());
        assertThat(second).isSameAs(first);
    }

    @Test
    void anEvolvedSchemaIsADifferentQuestionAboutTheSameDocument() {
        llmReturns(MATCH_RESPONSE);

        extractionService.extractWithSchema(parsed(), SCHEMA, HASH);
        // A concurrent upload added a column, so the prompt — and the answer — changed.
        extractionService.extractWithSchema(parsed(), SCHEMA.withColumns(List.of(
                new SchemaColumn("Vendor", ColumnType.TEXT, null, true),
                new SchemaColumn("Tax", ColumnType.CURRENCY, null, false))), HASH);

        verify(llmClient, times(2)).callJson(anyString(), any(), anyInt());
    }

    @Test
    void inferenceAndMatchingDoNotShareEntries() {
        llmReturns(INFERENCE_RESPONSE);
        extractionService.inferAndExtract(parsed(), HASH);

        llmReturns(MATCH_RESPONSE);
        SchemaMatchResult matched = extractionService.extractWithSchema(parsed(), SCHEMA, HASH);

        // Same content hash, but an inference result must never be served as a match.
        assertThat(matched.rows()).hasSize(1);
        verify(llmClient, times(2)).callJson(anyString(), any(), anyInt());
    }

    @Test
    void anEmptyDocumentIsRejectedWithoutCallingTheLlm() {
        assertThatThrownBy(() -> extractionService.inferAndExtract(
                ParseResult.ofText("", DocumentFormat.TEXT, Map.of()), HASH))
                .hasMessageContaining("empty");

        verifyNoInteractions(llmClient);
    }
}
