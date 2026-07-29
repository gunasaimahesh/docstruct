package com.docstruct.domain.schema;

import java.util.List;

import com.docstruct.domain.ConfidenceLevel;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** The inferred schema of a document collection. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DocumentSchema(
        List<SchemaColumn> columns,
        String documentType,
        ConfidenceLevel confidence
) {
    public DocumentSchema withColumns(List<SchemaColumn> newColumns) {
        return new DocumentSchema(newColumns, documentType, confidence);
    }
}
