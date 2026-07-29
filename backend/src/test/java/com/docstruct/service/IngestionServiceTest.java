package com.docstruct.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.docstruct.config.UploadProperties;
import com.docstruct.domain.CollectionEntity;
import com.docstruct.domain.ColumnType;
import com.docstruct.domain.ConfidenceLevel;
import com.docstruct.domain.DocumentFormat;
import com.docstruct.domain.ImportanceLevel;
import com.docstruct.domain.extraction.ExtractionCell;
import com.docstruct.domain.extraction.SchemaMatchResult;
import com.docstruct.domain.schema.DocumentSchema;
import com.docstruct.domain.schema.SchemaColumn;
import com.docstruct.dto.UploadResponse;
import com.docstruct.parser.ParseResult;
import com.docstruct.parser.ParserService;
import com.docstruct.repository.CollectionRepository;
import com.docstruct.repository.DocumentRepository;
import com.docstruct.repository.DynamicTableRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class IngestionServiceTest {

    @Mock
    private ParserService parserService;
    @Mock
    private ExtractionService extractionService;
    @Mock
    private CollectionRepository collectionRepository;
    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private DynamicTableRepository dynamicTableRepository;
    @Mock
    private TransactionTemplate transactionTemplate;

    private IngestionService ingestionService;
    private CollectionEntity collection;

    private final MockMultipartFile file = new MockMultipartFile(
            "file", "invoices.csv", "text/csv", "Vendor\nAcme".getBytes(StandardCharsets.UTF_8));

    @BeforeEach
    void setUp() {
        ingestionService = new IngestionService(
                parserService, extractionService, collectionRepository, documentRepository,
                dynamicTableRepository, transactionTemplate,
                new UploadProperties(10 * 1024 * 1024), new ObjectMapper());

        collection = CollectionEntity.create("Invoices", null, new DocumentSchema(
                List.of(new SchemaColumn("Vendor", ColumnType.TEXT, null, true)),
                "invoice", ConfidenceLevel.HIGH));
    }

    // ---- Collection naming (pure logic) ----

    @Test
    void generatesNameFromTypeAndFilename() {
        assertThat(IngestionService.generateCollectionName("acme_march.pdf", "invoice"))
                .isEqualTo("invoice — acme march");
    }

    @Test
    void keepsFilenameWhenItAlreadyContainsType() {
        assertThat(IngestionService.generateCollectionName("Invoice-2026-03.pdf", "invoice"))
                .isEqualTo("Invoice 2026 03");
    }

    @Test
    void replacesUnderscoresInDocumentType() {
        assertThat(IngestionService.generateCollectionName("statement.csv", "bank_statement"))
                .isEqualTo("bank statement — statement");
    }

    // ---- Schema evolution ----

    private void mockIngestionPipeline(List<SchemaColumn> newColumns) {
        when(parserService.parse(any(), anyString(), anyString()))
                .thenReturn(ParseResult.ofText("Vendor\nAcme", DocumentFormat.CSV, Map.of()));
        when(extractionService.extractWithSchema(any(), any()))
                .thenReturn(new SchemaMatchResult(
                        List.of(Map.of("Vendor",
                                ExtractionCell.of("Acme", ConfidenceLevel.HIGH, ImportanceLevel.HIGH))),
                        newColumns,
                        List.of()));
        when(collectionRepository.findById(collection.getId())).thenReturn(Optional.of(collection));
        when(transactionTemplate.execute(any())).thenAnswer(inv ->
                inv.<TransactionCallback<UploadResponse>>getArgument(0)
                        .doInTransaction(new SimpleTransactionStatus()));
        when(documentRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(dynamicTableRepository.insertRows(anyString(), anyString(), anyList(), anyList(), any()))
                .thenReturn(1);
    }

    @Test
    void newColumnIsAddedToSchemaAndDataTable() {
        SchemaColumn tax = new SchemaColumn("Tax", ColumnType.CURRENCY, "Tax amount", false);
        mockIngestionPipeline(List.of(tax));

        UploadResponse response = ingestionService.ingestIntoCollection(collection.getId(), file);

        verify(dynamicTableRepository).addColumn(collection.getId(), tax);
        assertThat(collection.getSchema().columns())
                .extracting(SchemaColumn::name)
                .containsExactly("Vendor", "Tax");
        assertThat(response.extraction().warnings())
                .anyMatch(w -> w.contains("Schema evolved: 1 new column(s) detected"));
    }

    @Test
    void caseInsensitiveDuplicateColumnsAreNotReAdded() {
        mockIngestionPipeline(List.of(new SchemaColumn("VENDOR", ColumnType.TEXT, null, false)));

        UploadResponse response = ingestionService.ingestIntoCollection(collection.getId(), file);

        verify(dynamicTableRepository, never()).addColumn(anyString(), any());
        assertThat(collection.getSchema().columns())
                .extracting(SchemaColumn::name)
                .containsExactly("Vendor");
        assertThat(response.extraction().warnings())
                .noneMatch(w -> w.contains("Schema evolved"));
    }

    @Test
    void mixOfNewAndDuplicateColumnsOnlyAddsTheNewOnes() {
        SchemaColumn duplicate = new SchemaColumn("vendor", ColumnType.TEXT, null, false);
        SchemaColumn tax = new SchemaColumn("Tax", ColumnType.CURRENCY, null, false);
        mockIngestionPipeline(List.of(duplicate, tax));

        UploadResponse response = ingestionService.ingestIntoCollection(collection.getId(), file);

        verify(dynamicTableRepository).addColumn(collection.getId(), tax);
        verify(dynamicTableRepository, never()).addColumn(collection.getId(), duplicate);
        assertThat(collection.getSchema().columns()).hasSize(2);
        assertThat(response.extraction().warnings())
                .anyMatch(w -> w.contains("Schema evolved: 1 new column(s) detected"));
    }

    @Test
    void noNewColumnsLeavesSchemaUntouched() {
        mockIngestionPipeline(List.of());
        DocumentSchema before = collection.getSchema();

        ingestionService.ingestIntoCollection(collection.getId(), file);

        verify(dynamicTableRepository, never()).addColumn(anyString(), any());
        assertThat(collection.getSchema()).isSameAs(before);
    }
}
