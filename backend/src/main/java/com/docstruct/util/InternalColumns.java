package com.docstruct.util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Internal bookkeeping columns (prefixed with '_': _row_id, _document_id,
 * _confidence, ...) are never exposed in API responses or exports.
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
