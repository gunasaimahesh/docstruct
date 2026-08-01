package com.docstruct.domain.extraction;

import java.util.List;
import java.util.Map;

import com.docstruct.domain.schema.SchemaColumn;

/**
 * Result of extracting a document against an existing collection schema.
 *
 * @param analysis the document's own semantics — the schema is shared across the
 *                 collection, but the document type and section layout are read
 *                 per document, so a second document is described in its own terms
 */
public record SchemaMatchResult(
        List<Map<String, ExtractionCell>> rows,
        List<SchemaColumn> newColumns,
        List<String> warnings,
        DocumentAnalysis analysis
) {
    public SchemaMatchResult(List<Map<String, ExtractionCell>> rows, List<SchemaColumn> newColumns,
                             List<String> warnings) {
        this(rows, newColumns, warnings, null);
    }
}
