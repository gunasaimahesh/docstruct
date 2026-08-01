package com.docstruct.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.docstruct.domain.ColumnType;
import com.docstruct.domain.ConfidenceLevel;
import com.docstruct.domain.QueryRole;
import com.docstruct.domain.schema.QueryHint;
import com.docstruct.domain.extraction.DocumentChunk;
import com.docstruct.domain.extraction.DocumentTypeInfo;
import com.docstruct.domain.extraction.ExtractionCell;
import com.docstruct.domain.extraction.ExtractionResult;
import com.docstruct.domain.extraction.KnowledgeSection;
import com.docstruct.domain.extraction.SchemaMatchResult;
import com.docstruct.domain.schema.DocumentSchema;
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
    void uncitedPlainValuesRecoverCitationWhenGroundedInTheDocument() throws Exception {
        JsonNode raw = objectMapper.readTree("""
                {
                  "document_type": "list",
                  "schema": {"columns": [{"name": "Name", "type": "text"}], "confidence": "medium"},
                  "rows": [{"Name": "Acme Corp"}]
                }
                """);

        ExtractionCell cell = mapper.toExtractionResult(raw, scorer).rows().get(0).get("Name");

        assertThat(cell.value()).isEqualTo("Acme Corp");
        assertThat(cell.confidence()).isEqualTo(ConfidenceLevel.HIGH);
        assertThat(cell.evidence().chunk()).isEqualTo(1);
        assertThat(cell.evidence().note()).contains("Source chunk recovered");
    }

    @Test
    void uncitedInventedPlainValuesStayLow() throws Exception {
        JsonNode raw = objectMapper.readTree("""
                {
                  "document_type": "list",
                  "schema": {"columns": [{"name": "Name", "type": "text"}], "confidence": "medium"},
                  "rows": [{"Name": "Globex Industries"}]
                }
                """);

        ExtractionCell cell = mapper.toExtractionResult(raw, scorer).rows().get(0).get("Name");

        assertThat(cell.confidence()).isEqualTo(ConfidenceLevel.LOW);
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

        SchemaMatchResult result = mapper.toSchemaMatchResult(raw, vendorSchema(), scorer);

        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().get(0).get("Vendor").confidence()).isEqualTo(ConfidenceLevel.HIGH);
        assertThat(result.newColumns()).hasSize(1);
        assertThat(result.newColumns().get(0).name()).isEqualTo("Tax");
        assertThat(result.newColumns().get(0).type()).isEqualTo(ColumnType.CURRENCY);
        assertThat(result.newColumns().get(0).required()).isFalse();
    }

    // ---- Document semantics: the type and section layout the UI renders ----

    @Test
    void mapsDocumentTypeAndKnowledgeSections() throws Exception {
        JsonNode raw = objectMapper.readTree("""
                {
                  "document_type": "income_tax_return",
                  "document_type_info": {"name": "Income Tax Return", "category": "Financial"},
                  "knowledge_sections": [
                    {"title": "Taxpayer Information", "description": "Identity of the taxpayer",
                     "fields": ["Name", "PAN"]},
                    {"title": "Tax Summary", "fields": ["Total Income"]}
                  ],
                  "schema": {"columns": [
                    {"name": "Name", "type": "text"},
                    {"name": "PAN", "type": "text"},
                    {"name": "Total Income", "type": "currency"}
                  ]},
                  "rows": []
                }
                """);

        ExtractionResult result = mapper.toExtractionResult(raw, scorer);

        assertThat(result.analysis().documentType())
                .isEqualTo(new DocumentTypeInfo("Income Tax Return", "Financial"));
        assertThat(result.analysis().knowledgeSections())
                .extracting(KnowledgeSection::title, KnowledgeSection::fields)
                .containsExactly(
                        tuple("Taxpayer Information", List.of("Name", "PAN")),
                        tuple("Tax Summary", List.of("Total Income")));
        assertThat(result.analysis().knowledgeSections().get(0).description())
                .isEqualTo("Identity of the taxpayer");
    }

    @Test
    void sectionFieldsAreResolvedAgainstTheRealColumns() throws Exception {
        JsonNode raw = objectMapper.readTree("""
                {
                  "document_type": "receipt",
                  "knowledge_sections": [
                    {"title": "Purchase", "fields": ["store_name", "invented_column", "Total"]},
                    {"title": "Duplicated", "fields": ["Total"]},
                    {"title": "Nothing Real", "fields": ["also_invented"]}
                  ],
                  "schema": {"columns": [
                    {"name": "Store Name", "type": "text"},
                    {"name": "Total", "type": "currency"}
                  ]},
                  "rows": []
                }
                """);

        List<KnowledgeSection> sections = mapper.toExtractionResult(raw, scorer)
                .analysis().knowledgeSections();

        // Loose naming is matched to real columns, a column the schema does not have is
        // dropped, a column claimed twice stays in the first section, and a section left
        // with nothing to show is not returned at all.
        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).title()).isEqualTo("Purchase");
        assertThat(sections.get(0).fields()).containsExactly("Store Name", "Total");
    }

    @Test
    void documentTypeFallsBackToTheSchemaTypeAndMissingSectionsAreFilledFromSchema() throws Exception {
        JsonNode raw = objectMapper.readTree("""
                {
                  "document_type": "bank_statement",
                  "schema": {"columns": [{"name": "Balance", "type": "currency"}]},
                  "rows": []
                }
                """);

        ExtractionResult result = mapper.toExtractionResult(raw, scorer);

        assertThat(result.analysis().documentType())
                .isEqualTo(new DocumentTypeInfo("Bank Statement", null));
        // No LLM sections → one section per schema column so Knowledge never goes blank.
        assertThat(result.analysis().knowledgeSections())
                .extracting(KnowledgeSection::title, KnowledgeSection::fields)
                .containsExactly(tuple("Balance", List.of("Balance")));
    }

    @Test
    void omittedColumnsAreAppendedSoKnowledgeNeverDropsExtractedData() throws Exception {
        JsonNode raw = objectMapper.readTree("""
                {
                  "document_type": "resume",
                  "knowledge_sections": [
                    {"title": "Experience", "fields": ["experience"]}
                  ],
                  "schema": {"columns": [
                    {"name": "summary", "type": "text"},
                    {"name": "programming_languages", "type": "text"},
                    {"name": "experience", "type": "entity_array",
                     "entitySchema": {"name": "job", "columns": [{"name": "company", "type": "text"}]}}
                  ]},
                  "rows": []
                }
                """);

        List<KnowledgeSection> sections = mapper.toExtractionResult(raw, scorer)
                .analysis().knowledgeSections();

        // Model only listed Experience; summary and skills still appear, titled from column names.
        assertThat(sections).extracting(KnowledgeSection::title)
                .containsExactly("Experience", "Summary", "Programming Languages");
        assertThat(sections).flatExtracting(KnowledgeSection::fields)
                .containsExactly("experience", "summary", "programming_languages");
    }

    @Test
    void schemaMatchReportsTheDocumentsOwnSemantics() throws Exception {
        JsonNode raw = objectMapper.readTree("""
                {
                  "rows": [],
                  "document_type_info": {"name": "Credit Note", "category": "Financial"},
                  "knowledge_sections": [{"title": "Vendor", "fields": ["Vendor"]}]
                }
                """);

        SchemaMatchResult result = mapper.toSchemaMatchResult(raw, vendorSchema(), scorer);

        assertThat(result.analysis().documentType().name()).isEqualTo("Credit Note");
        assertThat(result.analysis().knowledgeSections()).singleElement()
                .extracting(KnowledgeSection::fields).isEqualTo(List.of("Vendor"));
    }

    @Test
    void mapsQueryHintOnInferredColumnsAndOmitsValues() throws Exception {
        JsonNode raw = objectMapper.readTree("""
                {
                  "document_type": "invoice",
                  "schema": {
                    "columns": [
                      {
                        "name": "Status",
                        "type": "text",
                        "required": true,
                        "queryHint": {
                          "filterable": true,
                          "sortable": true,
                          "groupable": true,
                          "role": "status",
                          "example": "Paid",
                          "values": ["Paid", "Unpaid"]
                        }
                      },
                      {
                        "name": "Total",
                        "type": "currency",
                        "required": true,
                        "queryHint": {
                          "filterable": true,
                          "sortable": true,
                          "role": "money",
                          "unit": "USD"
                        }
                      },
                      {
                        "name": "Summary",
                        "type": "text",
                        "required": false,
                        "queryHint": {
                          "filterable": false,
                          "sortable": false,
                          "role": "description"
                        }
                      },
                      {
                        "name": "Line Items",
                        "type": "entity_array",
                        "required": false,
                        "queryHint": { "role": "status" },
                        "entitySchema": {"name": "line_items", "columns": [
                          {"name": "Description", "type": "text"}
                        ]}
                      }
                    ],
                    "confidence": "high"
                  },
                  "rows": []
                }
                """);

        ExtractionResult result = mapper.toExtractionResult(raw, scorer);
        SchemaColumn status = result.schema().columns().get(0);
        SchemaColumn total = result.schema().columns().get(1);
        SchemaColumn summary = result.schema().columns().get(2);
        SchemaColumn lines = result.schema().columns().get(3);

        assertThat(status.queryHint()).isEqualTo(
                new QueryHint(true, true, true, QueryRole.STATUS, null, "Paid"));
        assertThat(status.isFilterable()).isTrue();
        assertThat(total.queryHint().role()).isEqualTo(QueryRole.MONEY);
        assertThat(total.queryHint().unit()).isEqualTo("USD");
        // Prose stays a filter/search target; the UI narrows it to contains-only.
        assertThat(summary.isFilterable()).isTrue();
        assertThat(summary.queryHint().role()).isEqualTo(QueryRole.DESCRIPTION);
        // entity_array columns drop queryHint — they are nested tables, not filter columns
        assertThat(lines.queryHint()).isNull();
        assertThat(lines.isFilterable()).isFalse();
    }

    @Test
    void mapsQueryHintOnNestedEntityColumns() throws Exception {
        JsonNode raw = objectMapper.readTree("""
                {
                  "document_type": "resume",
                  "schema": {
                    "columns": [
                      {
                        "name": "Experience",
                        "type": "entity_array",
                        "required": false,
                        "entitySchema": {
                          "name": "Experience",
                          "columns": [
                            {
                              "name": "Company",
                              "type": "text",
                              "queryHint": {
                                "filterable": true,
                                "sortable": true,
                                "groupable": true,
                                "role": "company",
                                "example": "Amazon"
                              }
                            },
                            {
                              "name": "Title",
                              "type": "text",
                              "queryHint": {
                                "filterable": true,
                                "sortable": true,
                                "role": "identifier"
                              }
                            }
                          ]
                        }
                      }
                    ],
                    "confidence": "high"
                  },
                  "rows": []
                }
                """);

        SchemaColumn experience = mapper.toExtractionResult(raw, scorer).schema().columns().get(0);
        SchemaColumn company = experience.entitySchema().columns().get(0);
        SchemaColumn title = experience.entitySchema().columns().get(1);

        assertThat(experience.queryHint()).isNull();
        assertThat(company.queryHint().role()).isEqualTo(QueryRole.COMPANY);
        assertThat(company.queryHint().groupable()).isTrue();
        assertThat(company.queryHint().example()).isEqualTo("Amazon");
        assertThat(title.queryHint().role()).isEqualTo(QueryRole.IDENTIFIER);
    }

    @Test
    void mapsQueryHintOnNewColumnsAndToleratesAbsence() throws Exception {
        JsonNode withHint = objectMapper.readTree("""
                {
                  "rows": [],
                  "new_columns": [
                    {
                      "name": "Currency",
                      "type": "text",
                      "description": "ISO currency",
                      "queryHint": {
                        "filterable": true,
                        "sortable": true,
                        "groupable": true,
                        "role": "currency",
                        "example": "USD"
                      }
                    }
                  ]
                }
                """);
        JsonNode withoutHint = objectMapper.readTree("""
                {
                  "rows": [],
                  "new_columns": [
                    { "name": "Tax", "type": "currency", "description": "Tax amount" }
                  ]
                }
                """);

        SchemaColumn currency = mapper.toSchemaMatchResult(withHint, vendorSchema(), scorer)
                .newColumns().get(0);
        SchemaColumn tax = mapper.toSchemaMatchResult(withoutHint, vendorSchema(), scorer)
                .newColumns().get(0);

        assertThat(currency.queryHint().role()).isEqualTo(QueryRole.CURRENCY);
        assertThat(currency.queryHint().example()).isEqualTo("USD");
        assertThat(tax.queryHint()).isNull();
        assertThat(tax.isFilterable()).isTrue();
    }

    private static DocumentSchema vendorSchema() {
        return new DocumentSchema(
                List.of(new SchemaColumn("Vendor", ColumnType.TEXT, null, true)),
                "invoice", ConfidenceLevel.HIGH);
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

    @Test
    void promotesTextColumnWhenValueIsArrayOfObjects() throws Exception {
        // Model typed Education as text but returned a structured row array — promote to table.
        JsonNode raw = objectMapper.readTree("""
                {
                  "document_type": "resume",
                  "schema": {"columns": [
                    {"name": "name", "type": "text"},
                    {"name": "Education", "type": "text"}
                  ], "confidence": "high"},
                  "rows": [{
                    "name": {"value": "Acme Corp", "page": 1, "chunk": 1, "confidence": "high",
                             "raw_source": "Acme Corp"},
                    "Education": {"value": [
                      {"institution": {"value": "IIT Roorkee", "page": 1, "chunk": 1,
                                       "confidence": "high", "raw_source": "IIT Roorkee"},
                       "degree": {"value": "B.Tech", "page": 1, "chunk": 1,
                                  "confidence": "high", "raw_source": "B.Tech"},
                       "gpa": {"value": "8.37/10", "page": 1, "chunk": 1,
                               "confidence": "high", "raw_source": "8.37/10"}}
                    ], "confidence": "high"}
                  }]
                }
                """);

        ExtractionResult result = mapper.toExtractionResult(raw, scorer);
        SchemaColumn education = result.schema().columns().stream()
                .filter(c -> c.name().equals("Education")).findFirst().orElseThrow();

        assertThat(education.type()).isEqualTo(ColumnType.ENTITY_ARRAY);
        assertThat(education.entitySchema().columns()).extracting(SchemaColumn::name)
                .contains("institution", "degree", "gpa");
        @SuppressWarnings("unchecked")
        List<Map<String, ExtractionCell>> nested =
                (List<Map<String, ExtractionCell>>) result.rows().get(0).get("Education").value();
        assertThat(nested.get(0).get("institution").value()).isEqualTo("IIT Roorkee");
    }

    @Test
    void flatRestructureCandidatesSkipIdentityAndShortText() throws Exception {
        JsonNode raw = objectMapper.readTree("""
                {
                  "document_type": "resume",
                  "schema": {"columns": [
                    {"name": "summary", "type": "text"},
                    {"name": "education", "type": "text"},
                    {"name": "email", "type": "email"}
                  ], "confidence": "high"},
                  "rows": [{
                    "summary": {"value": "Software engineer with two years of backend experience building APIs.",
                                "page": 1, "chunk": 1, "confidence": "high",
                                "raw_source": "Software engineer with two years"},
                    "education": {"value": "Indian Institute of Technology, Roorkee 2024 B.Tech in Computer Science and Engineering | CGPA: 8.37/10",
                                  "page": 1, "chunk": 1, "confidence": "high",
                                  "raw_source": "Indian Institute of Technology"},
                    "email": {"value": "billing@acme.com", "page": 1, "chunk": 2, "confidence": "high",
                              "raw_source": "billing@acme.com"}
                  }]
                }
                """);

        ExtractionResult result = mapper.toExtractionResult(raw, scorer);
        List<Map<String, Object>> candidates = mapper.flatRestructureCandidates(result);

        assertThat(candidates).extracting(c -> c.get("column")).containsExactly("education");
    }

    @Test
    void applyRestructureReplacesFlatTextWithEntityArray() throws Exception {
        JsonNode raw = objectMapper.readTree("""
                {
                  "document_type": "resume",
                  "schema": {"columns": [{"name": "education", "type": "text"}], "confidence": "high"},
                  "rows": [{
                    "education": {"value": "Indian Institute of Technology, Roorkee 2024 B.Tech in Computer Science and Engineering | CGPA: 8.37/10",
                                  "page": 1, "chunk": 1, "confidence": "high",
                                  "raw_source": "Indian Institute of Technology, Roorkee 2024"}
                  }]
                }
                """);
        ExtractionResult original = mapper.toExtractionResult(raw, scorer);

        JsonNode repair = objectMapper.readTree("""
                {
                  "restructured": {
                    "education": {
                      "entitySchema": {
                        "name": "Education",
                        "description": "Academic record",
                        "columns": [
                          {"name": "institution", "type": "text", "required": true},
                          {"name": "degree", "type": "text", "required": true},
                          {"name": "gpa", "type": "text", "required": false}
                        ]
                      },
                      "rows": [{
                        "institution": {"value": "Indian Institute of Technology, Roorkee", "page": 1, "chunk": 1,
                                        "confidence": "high", "raw_source": "Indian Institute of Technology, Roorkee"},
                        "degree": {"value": "B.Tech in Computer Science and Engineering", "page": 1, "chunk": 1,
                                   "confidence": "high", "raw_source": "B.Tech in Computer Science and Engineering"},
                        "gpa": {"value": "8.37/10", "page": 1, "chunk": 1,
                                "confidence": "high", "raw_source": "CGPA: 8.37/10"}
                      }]
                    }
                  }
                }
                """);

        ExtractionResult repaired = mapper.applyRestructure(original, repair, scorer);
        SchemaColumn education = repaired.schema().columns().get(0);
        assertThat(education.type()).isEqualTo(ColumnType.ENTITY_ARRAY);
        @SuppressWarnings("unchecked")
        List<Map<String, ExtractionCell>> nested =
                (List<Map<String, ExtractionCell>>) repaired.rows().get(0).get("education").value();
        assertThat(nested.get(0).get("institution").value())
                .isEqualTo("Indian Institute of Technology, Roorkee");
        assertThat(nested.get(0).get("gpa").value()).isEqualTo("8.37/10");
    }
}
