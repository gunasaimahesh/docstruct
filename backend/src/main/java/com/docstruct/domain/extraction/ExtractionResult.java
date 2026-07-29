package com.docstruct.domain.extraction;

import java.util.List;
import java.util.Map;

import com.docstruct.domain.schema.DocumentSchema;

/** Result of a full schema-inference + extraction run on a document. */
public record ExtractionResult(
        DocumentSchema schema,
        List<Map<String, ExtractionCell>> rows,
        DocumentAnalysis analysis,
        List<String> warnings
) {
    public int rowCount() {
        return rows.size();
    }
}
