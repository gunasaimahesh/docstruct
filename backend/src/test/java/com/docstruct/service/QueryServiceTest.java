package com.docstruct.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.docstruct.domain.CollectionEntity;
import com.docstruct.domain.ColumnType;
import com.docstruct.domain.ConfidenceLevel;
import com.docstruct.domain.schema.DocumentSchema;
import com.docstruct.domain.schema.EntitySchema;
import com.docstruct.domain.schema.SchemaColumn;
import com.docstruct.dto.QueryResponse.QueryResultDto;
import com.docstruct.exception.QueryException;
import com.docstruct.llm.LlmClient;
import com.docstruct.repository.DynamicTableRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class QueryServiceTest {

    @Mock
    private CollectionService collectionService;

    @Mock
    private DynamicTableRepository dynamicTableRepository;

    @Mock
    private LlmClient llmClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private QueryService queryService;
    private CollectionEntity collection;
    private String mainTable;
    private String childTable;

    @BeforeEach
    void setUp() {
        queryService = new QueryService(collectionService, dynamicTableRepository, llmClient, objectMapper);
        collection = CollectionEntity.create("Invoices", null, new DocumentSchema(
                List.of(new SchemaColumn("Vendor", ColumnType.TEXT, null, true),
                        new SchemaColumn("Total", ColumnType.CURRENCY, null, true),
                        new SchemaColumn("Line Items", ColumnType.ENTITY_ARRAY, null, false,
                                new EntitySchema("Line Items", null,
                                        List.of(new SchemaColumn("Description", ColumnType.TEXT, null, true))))),
                "invoice", ConfidenceLevel.HIGH));
        mainTable = DynamicTableRepository.dataTableName(collection.getId());
        childTable = DynamicTableRepository.dataTableName(collection.getId(), "Line Items");
        when(collectionService.getOrThrow(collection.getId())).thenReturn(collection);
    }

    private void mockGeneratedSql(String sql) {
        when(llmClient.callJson(anyString(), any(), anyInt()))
                .thenReturn(objectMapper.createObjectNode()
                        .put("sql", sql)
                        .put("explanation", "test"));
    }

    @Test
    void rejectsNonSelectStatements() {
        mockGeneratedSql("DELETE FROM \"" + mainTable + "\"");

        assertThatThrownBy(() -> queryService.query(collection.getId(), "delete everything"))
                .isInstanceOf(QueryException.class)
                .hasMessageContaining("Only SELECT queries are allowed");
    }

    @Test
    void rejectsForbiddenKeywords() {
        mockGeneratedSql("SELECT * FROM \"" + mainTable + "\"; DROP TABLE collections");

        assertThatThrownBy(() -> queryService.query(collection.getId(), "sneaky"))
                .isInstanceOf(QueryException.class)
                .hasMessageContaining("forbidden operation: DROP");
    }

    @Test
    void rejectsOtherCollectionsDataTables() {
        mockGeneratedSql("SELECT * FROM \"data_someone_elses_collection\"");

        assertThatThrownBy(() -> queryService.query(collection.getId(), "show me everything"))
                .isInstanceOf(QueryException.class)
                .hasMessageContaining("outside this collection");
    }

    @Test
    void rejectsMetadataTables() {
        mockGeneratedSql("SELECT * FROM collections");

        assertThatThrownBy(() -> queryService.query(collection.getId(), "list all collections"))
                .isInstanceOf(QueryException.class)
                .hasMessageContaining("outside this collection");
    }

    @Test
    void rejectsSystemCatalogs() {
        mockGeneratedSql("SELECT tablename FROM pg_catalog.pg_tables");

        assertThatThrownBy(() -> queryService.query(collection.getId(), "what tables exist"))
                .isInstanceOf(QueryException.class)
                .hasMessageContaining("system tables");
    }

    @Test
    void rejectsTablesSmuggledOutsideFromClause() {
        // Foreign table hidden inside a subquery: must still be caught
        mockGeneratedSql("SELECT * FROM \"" + mainTable
                + "\" WHERE \"Vendor\" IN (SELECT \"Vendor\" FROM \"data_other_collection\")");

        assertThatThrownBy(() -> queryService.query(collection.getId(), "compare with another collection"))
                .isInstanceOf(QueryException.class)
                .hasMessageContaining("outside this collection");
    }

    @Test
    void allowsJoinsToOwnChildEntityTables() {
        String sql = "SELECT p.\"Vendor\", c.\"Description\" FROM \"" + mainTable + "\" p "
                + "JOIN \"" + childTable + "\" c ON c._parent_row_id = p._row_id";
        mockGeneratedSql(sql);
        when(dynamicTableRepository.executeSelect(anyString()))
                .thenReturn(new DynamicTableRepository.QueryResultRows(
                        List.of("Vendor", "Description"),
                        List.of(Map.of("Vendor", "Acme", "Description", "Widget"))));
        when(llmClient.callText(anyString(), anyDouble(), anyInt())).thenReturn("One line item.");

        QueryResultDto result = queryService.query(collection.getId(), "vendors with line items");

        assertThat(result.rowCount()).isEqualTo(1);
    }

    @Test
    void allowsColumnsNamedLikeKeywords() {
        // "created_at" contains CREATE as a substring but must NOT be rejected
        mockGeneratedSql("SELECT \"created_at\" FROM \"" + mainTable + "\"");
        when(dynamicTableRepository.executeSelect(contains("created_at")))
                .thenReturn(new DynamicTableRepository.QueryResultRows(
                        List.of("created_at"), List.of(Map.of("created_at", "2026-01-01"))));
        when(llmClient.callText(anyString(), anyDouble(), anyInt())).thenReturn("One row found.");

        QueryResultDto result = queryService.query(collection.getId(), "when was it created");

        assertThat(result.rowCount()).isEqualTo(1);
        assertThat(result.summary()).isEqualTo("One row found.");
    }

    @Test
    void filtersInternalColumnsAndFallsBackOnSummaryFailure() {
        mockGeneratedSql("SELECT * FROM \"" + mainTable + "\"");
        when(dynamicTableRepository.executeSelect(anyString()))
                .thenReturn(new DynamicTableRepository.QueryResultRows(
                        List.of("vendor"),
                        List.of(Map.of("vendor", "Acme", "_row_id", 1L, "_confidence", "high"))));
        when(llmClient.callText(anyString(), anyDouble(), anyInt()))
                .thenThrow(new RuntimeException("LLM down"));

        QueryResultDto result = queryService.query(collection.getId(), "list vendors");

        assertThat(result.rows().get(0)).containsOnlyKeys("vendor");
        assertThat(result.summary()).isEqualTo("Found 1 results.");
        assertThat(result.generatedSql()).contains("SELECT");
    }
}
