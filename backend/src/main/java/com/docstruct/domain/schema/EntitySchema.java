package com.docstruct.domain.schema;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** Schema of a nested entity table (e.g. line items within an invoice). */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record EntitySchema(
        String name,
        String description,
        List<SchemaColumn> columns
) {
}
