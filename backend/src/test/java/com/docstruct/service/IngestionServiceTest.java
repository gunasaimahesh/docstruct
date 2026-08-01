package com.docstruct.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
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
import com.docstruct.exception.ConcurrentSchemaUpdateException;
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
        mockIngestionPipeline(newColumns, IngestionServiceTest::commit);
    }

    private void mockIngestionPipeline(List<SchemaColumn> newColumns, Answer<UploadResponse> commitBehaviour) {
        when(parserService.parse(any(), anyString(), anyString()))
                .thenReturn(ParseResult.ofText("Vendor\nAcme", DocumentFormat.CSV, Map.of()));
        when(extractionService.extractWithSchema(any(), any(), anyString()))
                .thenReturn(new SchemaMatchResult(
                        List.of(Map.of("Vendor",
                                ExtractionCell.of("Acme", ConfidenceLevel.HIGH, ImportanceLevel.HIGH))),
                        newColumns,
                        List.of()));
        when(collectionRepository.findById(collection.getId())).thenReturn(Optional.of(collection));
        when(transactionTemplate.execute(any())).thenAnswer(commitBehaviour);
        when(documentRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(dynamicTableRepository.insertRows(anyString(), anyString(), anyList(), anyList(), any()))
                .thenReturn(1);
    }

    private static UploadResponse commit(InvocationOnMock invocation) {
        return invocation.<TransactionCallback<UploadResponse>>getArgument(0)
                .doInTransaction(new SimpleTransactionStatus());
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

    // ---- Concurrent schema evolution (optimistic locking) ----

    /**
     * Stands in for the commit of a transaction that lost an optimistic-lock race:
     * the attempt's own schema change is discarded and the concurrent upload's column
     * is left in the collection instead, which is what the retry will re-read.
     * Later commits succeed, so {@code winnerColumn == null} means every commit conflicts.
     */
    private Answer<UploadResponse> firstCommitLosesTheRaceTo(SchemaColumn winnerColumn) {
        AtomicInteger commits = new AtomicInteger();
        return invocation -> {
            DocumentSchema beforeAttempt = collection.getSchema();
            UploadResponse result = commit(invocation);
            if (winnerColumn != null && commits.incrementAndGet() > 1) {
                return result;
            }
            List<SchemaColumn> rolledBack = winnerColumn == null
                    ? beforeAttempt.columns()
                    : Stream.concat(beforeAttempt.columns().stream(), Stream.of(winnerColumn)).toList();
            collection.updateSchema(beforeAttempt.withColumns(rolledBack));
            throw new ObjectOptimisticLockingFailureException(CollectionEntity.class, collection.getId());
        };
    }

    @Test
    void losingASchemaRaceRetriesTheMergeSoBothNewColumnsSurvive() {
        SchemaColumn tax = new SchemaColumn("Tax", ColumnType.CURRENCY, "Tax amount", false);
        SchemaColumn currency = new SchemaColumn("Currency", ColumnType.TEXT, null, false);
        mockIngestionPipeline(List.of(tax), firstCommitLosesTheRaceTo(currency));

        UploadResponse response = ingestionService.ingestIntoCollection(collection.getId(), file);

        // The winner's column is not overwritten and this upload's column is not dropped.
        assertThat(collection.getSchema().columns())
                .extracting(SchemaColumn::name)
                .containsExactly("Vendor", "Currency", "Tax");

        // The retry re-runs the DDL, which is why addColumn has to stay idempotent.
        verify(dynamicTableRepository, times(2)).addColumn(collection.getId(), tax);

        // Warnings are rebuilt per attempt rather than accumulated across them.
        assertThat(response.extraction().warnings())
                .containsExactly("Schema evolved: 1 new column(s) detected");
    }

    @Test
    void aColumnAlreadyAddedByTheRaceWinnerIsNotMergedTwice() {
        SchemaColumn tax = new SchemaColumn("Tax", ColumnType.CURRENCY, null, false);
        // Both uploads detected the same new column; the other one committed first.
        mockIngestionPipeline(List.of(tax), firstCommitLosesTheRaceTo(tax));

        UploadResponse response = ingestionService.ingestIntoCollection(collection.getId(), file);

        assertThat(collection.getSchema().columns())
                .extracting(SchemaColumn::name)
                .containsExactly("Vendor", "Tax");
        assertThat(response.extraction().warnings())
                .noneMatch(w -> w.contains("Schema evolved"));
    }

    @Test
    void unresolvedConflictFailsLoudlyInsteadOfDroppingTheColumn() {
        SchemaColumn tax = new SchemaColumn("Tax", ColumnType.CURRENCY, null, false);
        mockIngestionPipeline(List.of(tax), firstCommitLosesTheRaceTo(null));

        assertThatThrownBy(() -> ingestionService.ingestIntoCollection(collection.getId(), file))
                .isInstanceOf(ConcurrentSchemaUpdateException.class)
                .hasMessageContaining("try again");

        // Bounded: one read before the LLM call, then one per attempt.
        verify(collectionRepository, times(IngestionService.SCHEMA_MERGE_MAX_ATTEMPTS + 1))
                .findById(collection.getId());
        verify(dynamicTableRepository, times(IngestionService.SCHEMA_MERGE_MAX_ATTEMPTS))
                .addColumn(collection.getId(), tax);
    }
}
