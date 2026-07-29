package com.docstruct.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.docstruct.domain.ColumnType;
import com.docstruct.domain.ConfidenceLevel;
import com.docstruct.domain.ImportanceLevel;
import com.docstruct.domain.extraction.ExtractionCell;
import com.docstruct.domain.schema.EntitySchema;
import com.docstruct.domain.schema.SchemaColumn;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Exercises the dynamic DDL and recursive insert logic against a real
 * PostgreSQL instance — the one component that mocks cannot meaningfully cover.
 */
@Testcontainers(disabledWithoutDocker = true)
class DynamicTableRepositoryIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final String COLLECTION_ID = "11111111-2222-3333-4444-555555555555";
    private static final String DOCUMENT_ID = "doc-1";

    private static JdbcTemplate jdbcTemplate;
    private static DynamicTableRepository repository;

    @BeforeAll
    static void setUp() {
        jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        repository = new DynamicTableRepository(jdbcTemplate, new ObjectMapper());

        // Minimal documents table to satisfy the foreign key on data tables
        jdbcTemplate.execute("CREATE TABLE documents (id VARCHAR(36) PRIMARY KEY)");
        jdbcTemplate.update("INSERT INTO documents (id) VALUES (?)", DOCUMENT_ID);
    }

    private static List<SchemaColumn> invoiceSchema() {
        return List.of(
                new SchemaColumn("Vendor", ColumnType.TEXT, null, true),
                new SchemaColumn("Total", ColumnType.CURRENCY, null, true),
                new SchemaColumn("Line Items", ColumnType.ENTITY_ARRAY, null, false,
                        new EntitySchema("Line Items", null, List.of(
                                new SchemaColumn("Description", ColumnType.TEXT, null, true),
                                new SchemaColumn("Amount", ColumnType.CURRENCY, null, true)))));
    }

    private static ExtractionCell cell(Object value) {
        return ExtractionCell.of(value, ConfidenceLevel.HIGH, ImportanceLevel.HIGH);
    }

    @Test
    void fullTableLifecycle() {
        List<SchemaColumn> columns = invoiceSchema();
        String mainTable = DynamicTableRepository.dataTableName(COLLECTION_ID);
        String childTable = DynamicTableRepository.dataTableName(COLLECTION_ID, "Line Items");

        // Create: one main table plus one child table for the nested entity
        repository.createDataTables(COLLECTION_ID, columns);
        assertThat(countTables(mainTable, childTable)).isEqualTo(2);

        // Insert: one parent row with two nested line items = 3 rows total
        Map<String, ExtractionCell> row = Map.of(
                "Vendor", cell("Acme Corp"),
                "Total", cell(1234.5),
                "Line Items", cell(List.of(
                        Map.of("Description", cell("Widget"), "Amount", cell(1000.0)),
                        Map.of("Description", cell("Gadget"), "Amount", cell(234.5)))));
        int inserted = repository.insertRows(
                COLLECTION_ID, DOCUMENT_ID, columns, List.of(row), ConfidenceLevel.HIGH);
        assertThat(inserted).isEqualTo(3);

        // Read back the parent row
        DynamicTableRepository.DataPage parents = repository.getRows(COLLECTION_ID, null, 10, 0);
        assertThat(parents.total()).isEqualTo(1);
        Map<String, Object> parent = parents.rows().get(0);
        assertThat(parent.get("vendor")).isEqualTo("Acme Corp");
        assertThat(parent.get("total")).isEqualTo(1234.5);
        assertThat(parent.get("_document_id")).isEqualTo(DOCUMENT_ID);

        // Child rows point at the parent via _parent_row_id
        DynamicTableRepository.DataPage children = repository.getRows(COLLECTION_ID, childTable, 10, 0);
        assertThat(children.total()).isEqualTo(2);
        assertThat(children.rows())
                .extracting(r -> r.get("_parent_row_id"))
                .containsOnly(parent.get("_row_id"));
        assertThat(children.rows())
                .extracting(r -> r.get("description"))
                .containsExactlyInAnyOrder("Widget", "Gadget");

        // Schema evolution: a new column becomes queryable immediately
        repository.addColumn(COLLECTION_ID, new SchemaColumn("Tax", ColumnType.CURRENCY, null, false));
        assertThat(repository.getRows(COLLECTION_ID, null, 10, 0).rows().get(0)).containsKey("tax");

        // Drop removes the main table and all child tables
        repository.dropTables(COLLECTION_ID, columns);
        assertThat(countTables(mainTable, childTable)).isZero();
    }

    private static Integer countTables(String... names) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name IN (?, ?)",
                Integer.class, (Object[]) names);
    }
}
