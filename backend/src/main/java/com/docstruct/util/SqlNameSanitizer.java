package com.docstruct.util;

/**
 * Sanitizes user/LLM-provided names for safe use as SQL identifiers.
 * Converts to snake_case, strips special characters, caps length at 63
 * (the PostgreSQL identifier limit).
 */
public final class SqlNameSanitizer {

    private SqlNameSanitizer() {
    }

    public static String sanitize(String name) {
        if (name == null) {
            return "unnamed_column";
        }
        String result = name.toLowerCase()
                .replaceAll("[^a-z0-9_\\s]", "")
                .replaceAll("\\s+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
        if (result.length() > 63) {
            result = result.substring(0, 63);
        }
        return result.isEmpty() ? "unnamed_column" : result;
    }
}
