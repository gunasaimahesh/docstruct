package com.docstruct.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The NL2SQL prompt teaches the model the envelope that QueryService parses.
 * A malformed example teaches malformed output, and a set of examples that only
 * refuses teaches a query box that refuses — so both are asserted here.
 */
class PromptTemplatesTest {

    private static final String TABLES = """
            Table: "data_abc123"
            Columns:
              "Vendor" (text)
            """;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void extractionPromptsAskForQueryHintWithoutValues() {
        String inference = PromptTemplates.schemaInference("Invoice…");
        String matching = PromptTemplates.schemaMatching("Invoice…", "{\"columns\":[]}", "invoice");

        for (String prompt : List.of(inference, matching)) {
            assertThat(prompt).contains("queryHint", "filterable", "sortable", "groupable", "role");
            assertThat(prompt).contains("status", "money", "description", "company");
            assertThat(prompt).containsIgnoringCase("never include a \"values\"");
            assertThat(prompt.toLowerCase()).contains("json");
        }
        // Nested entity attributes are first-class filter targets
        assertThat(inference).contains("Nested Attribute").contains("entitySchema");
    }

    @Test
    void queryPromptStatesBothEnvelopeShapes() {
        String prompt = PromptTemplates.queryToSql("total unpaid", TABLES);

        assertThat(prompt).contains("\"answerable\": false", "\"answerable\": true", "\"reason\"");
        assertThat(prompt).contains(TABLES).endsWith("QUESTION: total unpaid");
        // OpenAI-compatible JSON mode rejects the request unless the word "json" appears
        assertThat(prompt.toLowerCase()).contains("json");
        // The rule that made "Hi" return rows must not creep back in
        assertThat(prompt).doesNotContain("best-effort query");
    }

    @Test
    void queryPromptExamplesAreValidJsonAndTeachBothDirections() throws Exception {
        List<JsonNode> examples = exampleEnvelopes(PromptTemplates.queryToSql("total unpaid", TABLES));

        assertThat(examples).hasSizeGreaterThanOrEqualTo(6);

        List<JsonNode> refusals = examples.stream().filter(n -> !n.path("answerable").asBoolean()).toList();
        List<JsonNode> answers = examples.stream().filter(n -> n.path("answerable").asBoolean()).toList();

        assertThat(refusals).hasSizeGreaterThanOrEqualTo(2);
        assertThat(refusals).allSatisfy(node -> assertThat(node.path("reason").asText()).isNotBlank());

        // Broad questions are answered, not refused: a prompt that only shows refusals
        // produces a query box that turns away "show me everything"
        assertThat(answers).hasSizeGreaterThanOrEqualTo(3);
        assertThat(answers).allSatisfy(node ->
                assertThat(node.path("sql").asText()).startsWith("SELECT").contains("data_abc123"));
        assertThat(answers).anySatisfy(node ->
                assertThat(node.path("sql").asText()).isEqualTo("SELECT * FROM \"data_abc123\""));
    }

    /** Every line of the prompt that is meant to be a JSON object the model copies. */
    private List<JsonNode> exampleEnvelopes(String prompt) throws Exception {
        List<JsonNode> envelopes = new ArrayList<>();
        for (String line : prompt.lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.startsWith("{\"answerable\"")) {
                envelopes.add(objectMapper.readTree(trimmed));
            }
        }
        return envelopes;
    }
}
