package com.docstruct.domain.extraction;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * What a reader would call this document, as opposed to the snake_case
 * {@code documentType} the schema is keyed by.
 *
 * @param name     human-readable name, e.g. "Income Tax Return"
 * @param category the document family, e.g. "Financial"
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DocumentTypeInfo(
        String name,
        String category
) {
}
