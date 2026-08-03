package com.docstruct.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JsonContentExtractorTest {

    @Test
    void stripsMarkdownFences() {
        assertThat(JsonContentExtractor.extract("```json\n{\"ok\":true}\n```"))
                .isEqualTo("{\"ok\":true}");
    }

    @Test
    void pullsObjectFromPreamble() {
        assertThat(JsonContentExtractor.extract("Sure! Here you go:\n{\"ok\": true, \"n\": 1}\nThanks"))
                .isEqualTo("{\"ok\": true, \"n\": 1}");
    }

    @Test
    void keepsNestedBracesInsideStrings() {
        String raw = "prefix {\"a\":\"x}y\",\"b\":{\"c\":1}} trailing";
        assertThat(JsonContentExtractor.extract(raw))
                .isEqualTo("{\"a\":\"x}y\",\"b\":{\"c\":1}}");
    }
}
