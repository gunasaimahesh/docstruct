package com.docstruct.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.docstruct.domain.ColumnType;
import com.docstruct.domain.ConfidenceLevel;
import com.docstruct.domain.extraction.ExtractionCell;
import com.docstruct.domain.extraction.ExtractionResult;
import com.docstruct.domain.extraction.SchemaMatchResult;
import com.docstruct.domain.schema.SchemaColumn;
import com.docstruct.exception.ExtractionException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class ExtractionResponseMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExtractionResponseMapper mapper = new ExtractionResponseMapper();

    @Test
    void mapsFullInferenceResponse() throws Exception {
        JsonNode raw = objectMapper.readTree("""
                {
                  "document_type": "invoice",
                  "document_analysis": {
                    "purpose": "Billing",
                    "detected_sections": ["header", "line items"]
                  },
                  "schema": {
                    "columns": [
                      {"name": "Vendor", "type": "text", "description": "Vendor name", "required": true},
                      {"name": "Total", "type": "currency", "required": true},
                      {"name": "Paid", "type": "boolean", "required": false},
                      {"name": "Line Items", "type": "entity_array", "required": false,
                       "entitySchema": {"name": "line_items", "columns": [
                          {"name": "Description", "type": "text"},
                          {"name": "Amount", "type": "currency"}
                       ]}}
                    ],
                    "confidence": "high"
                  },
                  "rows": [
                    {
                      "Vendor": {"value": "Acme Corp", "confidence": "high", "importance": "high", "raw_source": "Acme Corp"},
                      "Total": {"value": "$1,234.50", "confidence": "medium"},
                      "Paid": {"value": "yes", "confidence": "low"},
                      "Line Items": {"value": [
                        {"Description": {"value": "Widget", "confidence": "high"},
                         "Amount": {"value": 1234.5, "confidence": "high"}}
                      ], "confidence": "high"}
                    }
                  ],
                  "warnings": ["date missing"]
                }
                """);

        ExtractionResult result = mapper.toExtractionResult(raw);

        assertThat(result.schema().documentType()).isEqualTo("invoice");
        assertThat(result.schema().confidence()).isEqualTo(ConfidenceLevel.HIGH);
        assertThat(result.schema().columns()).hasSize(4);
        assertThat(result.schema().columns().get(3).entitySchema().columns()).hasSize(2);
        assertThat(result.warnings()).containsExactly("date missing");
        assertThat(result.analysis().purpose()).isEqualTo("Billing");

        Map<String, ExtractionCell> row = result.rows().get(0);
        assertThat(row.get("Vendor").value()).isEqualTo("Acme Corp");
        assertThat(row.get("Vendor").rawSource()).isEqualTo("Acme Corp");
        assertThat(row.get("Total").value()).isEqualTo(1234.5); // currency symbol stripped
        assertThat(row.get("Paid").value()).isEqualTo(Boolean.TRUE); // "yes" coerced

        @SuppressWarnings("unchecked")
        List<Map<String, ExtractionCell>> lineItems =
                (List<Map<String, ExtractionCell>>) row.get("Line Items").value();
        assertThat(lineItems).hasSize(1);
        assertThat(lineItems.get(0).get("Description").value()).isEqualTo("Widget");
    }

    @Test
    void missingCellsGetNullValueAndLowConfidence() throws Exception {
        JsonNode raw = objectMapper.readTree("""
                {
                  "document_type": "receipt",
                  "schema": {"columns": [{"name": "Store", "type": "text"}, {"name": "Total", "type": "number"}],
                             "confidence": "medium"},
                  "rows": [{"Store": {"value": "Shop"}}]
                }
                """);

        ExtractionResult result = mapper.toExtractionResult(raw);
        ExtractionCell missing = result.rows().get(0).get("Total");

        assertThat(missing.value()).isNull();
        assertThat(missing.confidence()).isEqualTo(ConfidenceLevel.LOW);
    }

    @Test
    void plainValuesAreWrappedWithMediumConfidence() throws Exception {
        JsonNode raw = objectMapper.readTree("""
                {
                  "document_type": "list",
                  "schema": {"columns": [{"name": "Name", "type": "text"}], "confidence": "medium"},
                  "rows": [{"Name": "plain string, not a cell object"}]
                }
                """);

        ExtractionCell cell = mapper.toExtractionResult(raw).rows().get(0).get("Name");
        assertThat(cell.value()).isEqualTo("plain string, not a cell object");
        assertThat(cell.confidence()).isEqualTo(ConfidenceLevel.MEDIUM);
    }

    @Test
    void emptySchemaThrowsExtractionException() throws Exception {
        JsonNode raw = objectMapper.readTree("""
                {"document_type": "unknown", "schema": {"columns": []}, "rows": []}
                """);

        assertThatThrownBy(() -> mapper.toExtractionResult(raw))
                .isInstanceOf(ExtractionException.class)
                .hasMessageContaining("Could not infer any schema columns");
    }

    @Test
    void schemaMatchCollectsNewColumns() throws Exception {
        List<SchemaColumn> existing = List.of(new SchemaColumn("Vendor", ColumnType.TEXT, null, true));
        JsonNode raw = objectMapper.readTree("""
                {
                  "rows": [{"Vendor": {"value": "Acme", "confidence": "high"}}],
                  "new_columns": [
                    {"name": "Tax", "type": "currency", "description": "Tax amount"},
                    {"name": "missing type is skipped"}
                  ],
                  "warnings": []
                }
                """);

        SchemaMatchResult result = mapper.toSchemaMatchResult(raw, existing);

        assertThat(result.rows()).hasSize(1);
        assertThat(result.newColumns()).hasSize(1);
        assertThat(result.newColumns().get(0).name()).isEqualTo("Tax");
        assertThat(result.newColumns().get(0).type()).isEqualTo(ColumnType.CURRENCY);
        assertThat(result.newColumns().get(0).required()).isFalse();
    }

    @Test
    void coercionHandlesEdgeCases() {
        assertThat(mapper.coerceValue(objectMapper.valueToTree("€ 1.234"), ColumnType.CURRENCY))
                .isEqualTo(1.234);
        assertThat(mapper.coerceValue(objectMapper.valueToTree("not a number"), ColumnType.NUMBER))
                .isNull();
        assertThat(mapper.coerceValue(objectMapper.valueToTree("n"), ColumnType.BOOLEAN))
                .isEqualTo(Boolean.FALSE);
        assertThat(mapper.coerceValue(objectMapper.valueToTree(""), ColumnType.TEXT))
                .isNull();
    }
}
