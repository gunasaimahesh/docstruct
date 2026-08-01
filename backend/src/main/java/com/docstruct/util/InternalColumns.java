package com.docstruct.util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Internal bookkeeping columns (prefixed with '_': _row_id, _document_id,
 * _confidence, ...) are never exposed in exports as raw columns.
 *
 * <p>Query answers do <em>not</em> use {@link #stripAll} blindly — see
 * {@code AnswerComposer}, which projects {@code _confidence_json} /
 * {@code _evidence_json} onto per-cell provenance before stripping the rest.
 */
public final class InternalColumns {

    private InternalColumns() {
    }

    public static Map<String, Object> strip(Map<String, Object> row) {
        Map<String, Object> clean = new LinkedHashMap<>();
        row.forEach((key, value) -> {
            if (!key.startsWith("_")) {
                clean.put(key, value);
            }
        });
        return clean;
    }

    public static List<Map<String, Object>> stripAll(List<Map<String, Object>> rows) {
        return rows.stream().map(InternalColumns::strip).toList();
    }
}
