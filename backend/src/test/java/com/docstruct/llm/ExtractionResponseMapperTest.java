package com.docstruct.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.docstruct.domain.ColumnType;
import com.docstruct.domain.ConfidenceLevel;
import com.docstruct.domain.extraction.DocumentChunk;
import com.docstruct.domain.extraction.ExtractionCell;
import com.docstruct.domain.extraction.ExtractionResult;
import com.docstruct.domain.extraction.SchemaMatchResult;
import com.docstruct.domain.schema.SchemaColumn;
import com.docstruct.exception.ExtractionException;
import com.docstruct.util.ConfidenceScorer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class ExtractionResponseMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExtractionResponseMapper mapper = new ExtractionResponseMapper();

    private static final List<DocumentChunk> CHUNKS = List.of(
            new DocumentChunk(1, 1, "Invoice INV-2041\nVendor: Acme Corp\nTotal: $1,234.50\nPaid: yes"),
            new DocumentChunk(2, 2, "Line Items\nWidget 1,000.00\nGadget 234.50"));

    private final ConfidenceScorer scorer = ConfidenceScorer.forChunks(CHUNKS);

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
                      "Vendor": {"value": "Acme Corp", "page": 1, "chunk": 1, "confidence": "high",
                                 "importance": "high", "raw_source": "Vendor: Acme Corp"},
                      "Total": {"value": "$1,234.50", "page": 1, "chunk": 1, "confidence": "medium",
                                "raw_source": "Total: $1,234.50"},
                      "Paid": {"value": "yes", "page": 1, "chunk": 1, "confidence": "low",
                               "raw_source": "Paid: yes"},
                      "Line Items": {"value": [
                        {"Description": {"value": "Widget", "page": 2, "chunk": 2, "confidence": "high",
                                         "raw_source": "Widget 1,000.00"},
                         "Amount": {"value": 1000.0, "page": 2, "chunk": 2, "confidence": "high",
                                    "raw_source": "Widget 1,000.00"}},
                        {"Description": {"value": "Gadget", "page": 2, "chunk": 2, "confidence": "high",
                                         "raw_source": "Gadget 234.50"},
                         "Amount": {"value": 234.5, "page": 2, "chunk": 2, "confidence": "high",
                                    "raw_source": "Gadget 234.50"}}
                      ], "confidence": "high"}
                    }
                  ],
                  "warnings": ["date missing"]
                }
                """);

        ExtractionResult result = mapper.toExtractionResult(raw, scorer);

        assertThat(result.schema().documentType()).isEqualTo("invoice");
        assertThat(result.schema().confidence()).isEqualTo(ConfidenceLevel.HIGH);
        assertThat(result.schema().columns()).hasSize(4);
        assertThat(result.schema().columns().get(3).entitySchema().columns()).hasSize(2);
        assertThat(result.warnings()).containsExactly("date missing");
        assertThat(result.analysis().purpose()).isEqualTo("Billing");

        Map<String, ExtractionCell> row = result.rows().get(0);
        assertThat(row.get("Vendor").value()).isEqualTo("Acme Corp");
        assertThat(row.get("Vendor").rawSource()).isEqualTo("Vendor: Acme Corp");
        assertThat(row.get("Total").value()).isEqualTo(1234.5); // currency symbol stripped
        assertThat(row.get("Paid").value()).isEqualTo(Boolean.TRUE); // "yes" coerced

        // Citation survives mapping and is scored
        assertThat(row.get("Vendor").evidence().page()).isEqualTo(1);
        assertThat(row.get("Vendor").evidence().chunk()).isEqualTo(1);
        assertThat(row.get("Vendor").evidence().score()).isEqualTo(1.0);
        assertThat(row.get("Vendor").confidence()).isEqualTo(ConfidenceLevel.HIGH);
        // Fully verified, so the model's own "low" only reduces it to medium
        assertThat(row.get("Paid").confidence()).isEqualTo(ConfidenceLevel.MEDIUM);

        @SuppressWarnings("unchecked")
        List<Map<String, ExtractionCell>> lineItems =
                (List<Map<String, ExtractionCell>>) row.get("Line Items").value();
        assertThat(lineItems).hasSize(2);
        assertThat(lineItems.get(0).get("Description").value()).isEqualTo("Widget");
        assertThat(lineItems.get(0).get("Description").evidence().page()).isEqualTo(2);
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

        ExtractionResult result = mapper.toExtractionResult(raw, scorer);
        ExtractionCell missing = result.rows().get(0).get("Total");

        assertThat(missing.value()).isNull();
        assertThat(missing.confidence()).isEqualTo(ConfidenceLevel.LOW);
        assertThat(missing.evidence().note()).contains("was found in the document");
        assertThat(missing.evidence().page()).isNull();
    }

    @Test
    void uncitedPlainValuesCannotReachHighConfidence() throws Exception {
        JsonNode raw = objectMapper.readTree("""
                {
                  "document_type": "list",
                  "schema": {"columns": [{"name": "Name", "type": "text"}], "confidence": "medium"},
                  "rows": [{"Name": "Acme Corp"}]
                }
                """);

        ExtractionCell cell = mapper.toExtractionResult(raw, scorer).rows().get(0).get("Name");

        assertThat(cell.value()).isEqualTo("Acme Corp");
        assertThat(cell.confidence()).isNotEqualTo(ConfidenceLevel.HIGH);
        assertThat(cell.evidence().note()).contains("No source chunk was cited");
    }

    @Test
    void emptySchemaThrowsExtractionException() throws Exception {
        JsonNode raw = objectMapper.readTree("""
                {"document_type": "unknown", "schema": {"columns": []}, "rows": []}
                """);

        assertThatThrownBy(() -> mapper.toExtractionResult(raw, scorer))
                .isInstanceOf(ExtractionException.class)
                .hasMessageContaining("Could not infer any schema columns");
    }

    @Test
    void schemaMatchCollectsNewColumns() throws Exception {
        List<SchemaColumn> existing = List.of(new SchemaColumn("Vendor", ColumnType.TEXT, null, true));
        JsonNode raw = objectMapper.readTree("""
                {
                  "rows": [{"Vendor": {"value": "Acme Corp", "page": 1, "chunk": 1, "confidence": "high",
                                       "raw_source": "Vendor: Acme Corp"}}],
                  "new_columns": [
                    {"name": "Tax", "type": "currency", "description": "Tax amount"},
                    {"name": "missing type is skipped"}
                  ],
                  "warnings": []
                }
                """);

        SchemaMatchResult result = mapper.toSchemaMatchResult(raw, existing, scorer);

        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().get(0).get("Vendor").confidence()).isEqualTo(ConfidenceLevel.HIGH);
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
