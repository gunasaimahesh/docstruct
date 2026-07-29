package com.docstruct.domain.extraction;

import java.util.List;
import java.util.Map;

import com.docstruct.domain.schema.SchemaColumn;

/** Result of extracting a document against an existing collection schema. */
public record SchemaMatchResult(
        List<Map<String, ExtractionCell>> rows,
        List<SchemaColumn> newColumns,
        List<String> warnings
) {
}
