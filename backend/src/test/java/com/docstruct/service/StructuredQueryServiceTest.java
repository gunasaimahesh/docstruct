package com.docstruct.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.docstruct.domain.ColumnType;
import com.docstruct.domain.ConfidenceLevel;
import com.docstruct.domain.CollectionEntity;
import com.docstruct.domain.schema.DocumentSchema;
import com.docstruct.domain.schema.EntitySchema;
import com.docstruct.domain.schema.SchemaColumn;
import com.docstruct.dto.FilterRequest;
import com.docstruct.dto.FilterRequest.FilterCondition;
import com.docstruct.dto.FilterRequest.SortSpec;
import com.docstruct.dto.QueryResponse.QueryResultDto;
import com.docstruct.exception.ValidationException;
import com.docstruct.repository.DynamicTableRepository;
import com.docstruct.repository.DynamicTableRepository.ChildFilterQuery;
import com.docstruct.repository.DynamicTableRepository.FilterClause;
import com.docstruct.repository.DynamicTableRepository.FilterPage;
import com.docstruct.repository.DynamicTableRepository.FilterQuery;
import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class StructuredQueryServiceTest {

    @Mock
    private CollectionService collectionService;

    @Mock
    private DynamicTableRepository dynamicTableRepository;

    private StructuredQueryService service;
    private CollectionEntity collection;

    @BeforeEach
    void setUp() {
        service = new StructuredQueryService(
                collectionService, dynamicTableRepository, new AnswerComposer(new ObjectMapper()));
        collection = CollectionEntity.create("Invoices", null, new DocumentSchema(
                List.of(
                        new SchemaColumn("Vendor", ColumnType.TEXT, null, true),
                        new SchemaColumn("Total", ColumnType.CURRENCY, null, true),
                        new SchemaColumn("Paid", ColumnType.BOOLEAN, null, false),
                        new SchemaColumn("Line Items", ColumnType.ENTITY_ARRAY, null, false,
                                new EntitySchema("Line Items", null,
                                        List.of(
                                                new SchemaColumn("Description", ColumnType.TEXT, null, true),
                                                new SchemaColumn("Company", ColumnType.TEXT, null, false))))),
                "invoice", ConfidenceLevel.HIGH));
        when(collectionService.getOrThrow(collection.getId())).thenReturn(collection);
    }

    private FilterPage emptyPage() {
        return new FilterPage(List.of(), 0, "SELECT main.* FROM \"data_x\" AS main");
    }

    /** Force the document-centric EXISTS path for nested filters. */
    private static FilterRequest asDocuments(FilterRequest request) {
        return new FilterRequest(
                request.filters(), request.match(), request.sort(),
                request.page(), request.limit(), request.excludeLowConfidence(), "documents");
    }

    private FilterQuery captureQuery(FilterRequest request) {
        when(dynamicTableRepository.filterRows(eq(collection.getId()), any()))
                .thenReturn(emptyPage());
        service.filter(collection.getId(), request);
        ArgumentCaptor<FilterQuery> captor = ArgumentCaptor.forClass(FilterQuery.class);
        verify(dynamicTableRepository).filterRows(eq(collection.getId()), captor.capture());
        return captor.getValue();
    }

    private ChildFilterQuery captureChildQuery(FilterRequest request) {
        when(dynamicTableRepository.filterChildRows(eq(collection.getId()), any()))
                .thenReturn(new FilterPage(List.of(), 0, "SELECT c.* FROM \"data_x_line_items\" AS c"));
        service.filter(collection.getId(), request);
        ArgumentCaptor<ChildFilterQuery> captor = ArgumentCaptor.forClass(ChildFilterQuery.class);
        verify(dynamicTableRepository).filterChildRows(eq(collection.getId()), captor.capture());
        return captor.getValue();
    }

    @Test
    void buildsEqualityClauseWithBoundParameter() {
        FilterQuery query = captureQuery(new FilterRequest(
                List.of(new FilterCondition("Vendor", "eq", "Acme")),
                "all", null, null, null));

        assertThat(query.clauses()).hasSize(1);
        FilterClause clause = query.clauses().get(0);
        assertThat(clause.sqlFragment()).isEqualTo("main.\"vendor\" = ?");
        assertThat(clause.params()).containsExactly("Acme");
        assertThat(query.matchAny()).isFalse();
        assertThat(query.sortColumn()).isEqualTo("_row_id");
    }

    @Test
    void buildsContainsAsIlikeWithEscapedMetacharacters() {
        FilterQuery query = captureQuery(new FilterRequest(
                List.of(new FilterCondition("Vendor", "contains", "100%_sure")),
                null, null, null, null));

        FilterClause clause = query.clauses().get(0);
        assertThat(clause.sqlFragment()).contains("ILIKE").contains("ESCAPE").contains("main.");
        assertThat(clause.params()).containsExactly("%100\\%\\_sure%");
    }

    @Test
    void coercesCurrencyValuesToNumbers() {
        FilterQuery query = captureQuery(new FilterRequest(
                List.of(new FilterCondition("Total", "gte", "$1,234.50")),
                "all", null, null, null));

        FilterClause clause = query.clauses().get(0);
        assertThat(clause.sqlFragment()).isEqualTo("main.\"total\" >= ?");
        assertThat(clause.params()).containsExactly(1234.5);
    }

    @Test
    void joinsConditionsWithOrWhenMatchIsAny() {
        FilterQuery query = captureQuery(new FilterRequest(
                List.of(
                        new FilterCondition("Vendor", "eq", "Acme"),
                        new FilterCondition("Vendor", "eq", "Globex")),
                "any", null, null, null));

        assertThat(query.clauses()).hasSize(2);
        assertThat(query.matchAny()).isTrue();
    }

    @Test
    void appliesSortOnSchemaColumn() {
        FilterQuery query = captureQuery(new FilterRequest(
                List.of(),
                null,
                new SortSpec("Total", "desc"),
                1, 50));

        assertThat(query.sortColumn()).isEqualTo("total");
        assertThat(query.sortDescending()).isTrue();
        assertThat(query.limit()).isEqualTo(50);
        assertThat(query.offset()).isZero();
    }

    @Test
    void emptyFiltersReturnTheWholeTable() {
        FilterQuery query = captureQuery(new FilterRequest(List.of(), null, null, null, null));

        assertThat(query.clauses()).isEmpty();
        assertThat(query.sortColumn()).isEqualTo("_row_id");
        assertThat(query.limit()).isEqualTo(100);
    }

    @Test
    void rejectsUnknownColumns() {
        assertThatThrownBy(() -> service.filter(collection.getId(), new FilterRequest(
                List.of(new FilterCondition("password", "eq", "x")),
                null, null, null, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Unknown or internal column");
    }

    @Test
    void rejectsInternalColumns() {
        assertThatThrownBy(() -> service.filter(collection.getId(), new FilterRequest(
                List.of(new FilterCondition("_document_id", "eq", "abc")),
                null, null, null, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Unknown or internal column");
    }

    @Test
    void rejectsEntityArrayColumnsAsMainTargets() {
        assertThatThrownBy(() -> service.filter(collection.getId(), new FilterRequest(
                List.of(new FilterCondition("Line Items", "eq", "x")),
                null, null, null, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Unknown or internal column");
    }

    @Test
    void buildsExistsClauseForNestedEntityColumn() {
        FilterQuery query = captureQuery(asDocuments(new FilterRequest(
                List.of(new FilterCondition("Company", "eq", "Amazon", "Line Items")),
                "all", null, null, null)));

        FilterClause clause = query.clauses().get(0);
        assertThat(clause.sqlFragment())
                .startsWith("EXISTS (SELECT 1 FROM \"data_")
                .contains("_line_items\" AS c WHERE c._parent_row_id = main._row_id")
                .contains("c.\"company\" = ?");
        assertThat(clause.params()).containsExactly("Amazon");
    }

    @Test
    void nestedFilterDefaultsToChildTableEntries() {
        ChildFilterQuery query = captureChildQuery(new FilterRequest(
                List.of(new FilterCondition("Company", "eq", "Amazon", "Line Items")),
                "all", null, null, null));

        assertThat(query.childEntityName()).isEqualTo("Line Items");
        assertThat(query.childClauses()).hasSize(1);
        assertThat(query.childClauses().get(0).sqlFragment()).isEqualTo("c.\"company\" = ?");
        assertThat(query.childClauses().get(0).params()).containsExactly("Amazon");
        assertThat(query.parentClauses()).isEmpty();
        assertThat(query.parentLabelColumn()).isEqualTo("vendor");
    }

    @Test
    void nestedContainsIsBoundInsideExists() {
        FilterQuery query = captureQuery(asDocuments(new FilterRequest(
                List.of(new FilterCondition("Description", "contains", "Widget", "Line Items")),
                null, null, null, null)));

        FilterClause clause = query.clauses().get(0);
        assertThat(clause.sqlFragment()).contains("EXISTS").contains("c.\"description\"").contains("ILIKE");
        assertThat(clause.params()).containsExactly("%Widget%");
    }

    @Test
    void sameEntityConditionsShareOneExistsWhenMatchAll() {
        FilterQuery query = captureQuery(asDocuments(new FilterRequest(
                List.of(
                        new FilterCondition("Company", "eq", "Amazon", "Line Items"),
                        new FilterCondition("Description", "contains", "Senior", "Line Items")),
                "all", null, null, null)));

        assertThat(query.clauses()).hasSize(1);
        FilterClause clause = query.clauses().get(0);
        assertThat(clause.sqlFragment())
                .containsOnlyOnce("EXISTS")
                .contains("c.\"company\" = ?")
                .contains("c.\"description\"")
                .contains(" AND ");
        assertThat(clause.params()).containsExactly("Amazon", "%Senior%");
    }

    @Test
    void sameEntityEntriesAndChildPredicatesTogether() {
        ChildFilterQuery query = captureChildQuery(new FilterRequest(
                List.of(
                        new FilterCondition("Company", "eq", "Amazon", "Line Items"),
                        new FilterCondition("Description", "contains", "Senior", "Line Items")),
                "all", null, null, null));

        assertThat(query.childClauses()).hasSize(2);
        assertThat(query.childClauses().get(0).params()).containsExactly("Amazon");
        assertThat(query.childClauses().get(1).params()).containsExactly("%Senior%");
        assertThat(query.matchAny()).isFalse();
    }

    @Test
    void sameEntityConditionsStaySeparateExistsWhenMatchAny() {
        FilterQuery query = captureQuery(asDocuments(new FilterRequest(
                List.of(
                        new FilterCondition("Company", "eq", "Amazon", "Line Items"),
                        new FilterCondition("Description", "contains", "Senior", "Line Items")),
                "any", null, null, null)));

        assertThat(query.clauses()).hasSize(2);
        assertThat(query.matchAny()).isTrue();
        assertThat(query.clauses().get(0).sqlFragment()).contains("EXISTS").contains("c.\"company\"");
        assertThat(query.clauses().get(1).sqlFragment()).contains("EXISTS").contains("c.\"description\"");
    }

    @Test
    void nestedEntriesReturnScopedChildRowsWithParentAndHeadline() {
        Map<String, Object> childRow = new LinkedHashMap<>();
        childRow.put("company", "Amazon");
        childRow.put("description", "SDE Intern");
        childRow.put("parent", "Acme");
        childRow.put("_row_id", 7L);
        childRow.put("_confidence_json", "{\"company\":\"high\",\"description\":\"medium\"}");
        childRow.put("_evidence_json",
                "{\"company\":{\"level\":\"high\",\"page\":1,\"rawSource\":\"Amazon\"}}");
        when(dynamicTableRepository.filterChildRows(eq(collection.getId()), any()))
                .thenReturn(new FilterPage(
                        List.of(childRow),
                        1,
                        "SELECT c.*, main.\"vendor\" AS parent FROM \"data_x_line_items\" AS c"));

        QueryResultDto result = service.filter(collection.getId(), new FilterRequest(
                List.of(new FilterCondition("Company", "eq", "Amazon", "Line Items")),
                null, null, null, null));

        assertThat(result.resultUnit()).isEqualTo("entries");
        assertThat(result.entityLabel()).isEqualTo("Line Items");
        assertThat(result.headline()).isEqualTo("Found 1 Line Items entry");
        assertThat(result.columns()).containsExactly("parent", "company", "description");
        assertThat(result.rows()).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> company = (Map<String, Object>) result.rows().get(0).get("company");
        assertThat(company).containsEntry("value", "Amazon").containsEntry("confidence", "high");
        @SuppressWarnings("unchecked")
        Map<String, Object> parent = (Map<String, Object>) result.rows().get(0).get("parent");
        assertThat(parent).containsEntry("value", "Acme");
        assertThat(parent).doesNotContainKey("confidence");
    }

    @Test
    void differentEntitiesStayAsSeparateExistsUnderMatchAll() {
        // Add a second entity_array so two nested groups don't collapse together.
        collection = CollectionEntity.create("Resumes", null, new DocumentSchema(
                List.of(
                        new SchemaColumn("Name", ColumnType.TEXT, null, true),
                        new SchemaColumn("Experience", ColumnType.ENTITY_ARRAY, null, false,
                                new EntitySchema("Experience", null,
                                        List.of(
                                                new SchemaColumn("Company", ColumnType.TEXT, null, true),
                                                new SchemaColumn("Title", ColumnType.TEXT, null, true)))),
                        new SchemaColumn("Education", ColumnType.ENTITY_ARRAY, null, false,
                                new EntitySchema("Education", null,
                                        List.of(new SchemaColumn("Degree", ColumnType.TEXT, null, true))))),
                "resume", ConfidenceLevel.HIGH));
        reset(collectionService);
        when(collectionService.getOrThrow(collection.getId())).thenReturn(collection);

        FilterQuery query = captureQuery(new FilterRequest(
                List.of(
                        new FilterCondition("Company", "eq", "Amazon", "Experience"),
                        new FilterCondition("Title", "contains", "Senior", "Experience"),
                        new FilterCondition("Degree", "contains", "PhD", "Education")),
                "all", null, null, null));

        assertThat(query.clauses()).hasSize(2);
        assertThat(query.clauses().get(0).sqlFragment())
                .containsOnlyOnce("EXISTS")
                .contains("_experience\"")
                .contains("c.\"company\"")
                .contains("c.\"title\"");
        assertThat(query.clauses().get(0).params()).containsExactly("Amazon", "%Senior%");
        assertThat(query.clauses().get(1).sqlFragment())
                .containsOnlyOnce("EXISTS")
                .contains("_education\"")
                .contains("c.\"degree\"");
        assertThat(query.clauses().get(1).params()).containsExactly("%PhD%");
    }

    @Test
    void rejectsUnknownEntity() {
        assertThatThrownBy(() -> service.filter(collection.getId(), new FilterRequest(
                List.of(new FilterCondition("Company", "eq", "x", "Not A Table")),
                null, null, null, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Unknown entity");
    }

    @Test
    void rejectsUnknownNestedColumn() {
        assertThatThrownBy(() -> service.filter(collection.getId(), new FilterRequest(
                List.of(new FilterCondition("password", "eq", "x", "Line Items")),
                null, null, null, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Unknown column");
    }

    @Test
    void rejectsUnknownOperators() {
        assertThatThrownBy(() -> service.filter(collection.getId(), new FilterRequest(
                List.of(new FilterCondition("Vendor", "regex", ".*")),
                null, null, null, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Unknown operator");
    }

    @Test
    void rejectsValueRequiredOperatorsWithoutAValue() {
        assertThatThrownBy(() -> service.filter(collection.getId(), new FilterRequest(
                List.of(new FilterCondition("Vendor", "eq", null)),
                null, null, null, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("requires a value");
    }

    @Test
    void rejectsNonNumericValueForCurrencyColumn() {
        assertThatThrownBy(() -> service.filter(collection.getId(), new FilterRequest(
                List.of(new FilterCondition("Total", "gt", "plenty")),
                null, null, null, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Expected a number");
    }

    @Test
    void rejectsUnknownSortColumn() {
        assertThatThrownBy(() -> service.filter(collection.getId(), new FilterRequest(
                List.of(),
                null,
                new SortSpec("_confidence", "asc"),
                null, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("sort column");
    }

    @Test
    void injectionStyleValueIsBoundNotInterpolated() {
        FilterQuery query = captureQuery(new FilterRequest(
                List.of(new FilterCondition("Vendor", "eq", "x\"; DROP TABLE collections; --")),
                null, null, null, null));

        FilterClause clause = query.clauses().get(0);
        assertThat(clause.sqlFragment()).isEqualTo("main.\"vendor\" = ?");
        assertThat(clause.params()).containsExactly("x\"; DROP TABLE collections; --");
        assertThat(clause.sqlFragment()).doesNotContain("DROP");
    }

    @Test
    void returnsGroundedRowsWithHeadline() {
        when(dynamicTableRepository.filterRows(eq(collection.getId()), any()))
                .thenReturn(new FilterPage(
                        List.of(Map.of(
                                "vendor", "Acme",
                                "_row_id", 1L,
                                "_confidence", "high",
                                "_confidence_json", "{\"vendor\":\"high\"}",
                                "_evidence_json", "{\"vendor\":{\"level\":\"high\",\"page\":2,\"rawSource\":\"Acme\"}}")),
                        1,
                        "SELECT main.* FROM \"data_x\" AS main WHERE (main.\"vendor\" = ?) ORDER BY main.\"_row_id\" ASC LIMIT ? OFFSET ?"));

        QueryResultDto result = service.filter(collection.getId(), new FilterRequest(
                List.of(new FilterCondition("Vendor", "eq", "Acme")),
                null, null, null, null));

        assertThat(result.answerable()).isTrue();
        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().get(0)).containsOnlyKeys("vendor");
        @SuppressWarnings("unchecked")
        Map<String, Object> cell = (Map<String, Object>) result.rows().get(0).get("vendor");
        assertThat(cell).containsEntry("value", "Acme").containsEntry("confidence", "high");
        assertThat(result.headline()).isEqualTo("Acme");
        assertThat(result.answerType()).isEqualTo("single_value");
        assertThat(result.generatedSql()).contains("SELECT");
    }

    @Test
    void isEmptyBindsNoParameters() {
        FilterQuery query = captureQuery(new FilterRequest(
                List.of(new FilterCondition("Vendor", "is_empty", null)),
                null, null, null, null));

        FilterClause clause = query.clauses().get(0);
        assertThat(clause.params()).isEmpty();
        assertThat(clause.sqlFragment()).contains("IS NULL");
    }

    @Test
    void distinctValuesUsesSanitizedSchemaColumn() {
        when(dynamicTableRepository.distinctValues(eq(collection.getId()), isNull(), eq("vendor"), eq(200)))
                .thenReturn(List.of("Acme", "Globex"));

        List<String> values = service.distinctValues(collection.getId(), "Vendor", null, null);

        assertThat(values).containsExactly("Acme", "Globex");
        verify(dynamicTableRepository).distinctValues(collection.getId(), null, "vendor", 200);
    }

    @Test
    void distinctValuesSupportsNestedEntityColumns() {
        when(dynamicTableRepository.distinctValues(
                eq(collection.getId()), eq("Line Items"), eq("company"), eq(200)))
                .thenReturn(List.of("Amazon", "HiLabs"));

        List<String> values = service.distinctValues(collection.getId(), "Company", "Line Items", null);

        assertThat(values).containsExactly("Amazon", "HiLabs");
        verify(dynamicTableRepository).distinctValues(collection.getId(), "Line Items", "company", 200);
    }

    @Test
    void distinctValuesRejectsUnknownAndEntityColumns() {
        assertThatThrownBy(() -> service.distinctValues(collection.getId(), "password", null, null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Unknown or internal column");
        assertThatThrownBy(() -> service.distinctValues(collection.getId(), "Line Items", null, 10))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Unknown or internal column");
    }
}
