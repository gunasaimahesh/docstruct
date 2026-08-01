package com.docstruct.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.docstruct.dto.QueryResponse.QueryResultDto;
import com.docstruct.service.AnswerComposer.ComposeOptions;
import com.fasterxml.jackson.databind.ObjectMapper;

class AnswerComposerTest {

    private final AnswerComposer composer = new AnswerComposer(new ObjectMapper());

    @Test
    void entityScopedHeadlineNamesTheNestedEntity() {
        QueryResultDto result = composer.compose(
                List.of(Map.of(
                        "company", "Amazon",
                        "title", "SDE",
                        "parent", "Guna",
                        "_confidence_json", "{}",
                        "_evidence_json", "{}")),
                "SELECT c.* …",
                "test",
                ComposeOptions.entries(1, false, "Experience"));

        assertThat(result.headline()).isEqualTo("Found 1 Experience entry");
        assertThat(result.resultUnit()).isEqualTo("entries");
        assertThat(result.entityLabel()).isEqualTo("Experience");
        assertThat(result.columns().get(0)).isEqualTo("parent");
    }

    @Test
    void formatEntityHeadlineHandlesPluralAndEmpty() {
        assertThat(AnswerComposer.formatEntityHeadline("Experience", 0))
                .isEqualTo("No matching Experience entries");
        assertThat(AnswerComposer.formatEntityHeadline("Experience", 3))
                .isEqualTo("Found 3 Experience entries");
    }
}
