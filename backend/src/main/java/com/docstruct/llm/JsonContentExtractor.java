package com.docstruct.llm;

/**
 * Best-effort recovery of a JSON object/array from messy LLM text
 * (markdown fences, preamble, truncated wrappers).
 */
final class JsonContentExtractor {

    private JsonContentExtractor() {
    }

    static String extract(String raw) {
        if (raw == null) {
            return "";
        }
        String text = stripCodeFences(raw.trim());
        if (text.isEmpty()) {
            return "";
        }
        if (looksLikeJson(text)) {
            return text;
        }
        int objectStart = text.indexOf('{');
        int arrayStart = text.indexOf('[');
        int start;
        char open;
        char close;
        if (objectStart < 0 && arrayStart < 0) {
            return text;
        }
        if (objectStart >= 0 && (arrayStart < 0 || objectStart < arrayStart)) {
            start = objectStart;
            open = '{';
            close = '}';
        } else {
            start = arrayStart;
            open = '[';
            close = ']';
        }
        int end = matchingClose(text, start, open, close);
        if (end < 0) {
            // Truncated output — return from the opening brace so the parse error is clearer.
            return text.substring(start).trim();
        }
        return text.substring(start, end + 1).trim();
    }

    static String stripCodeFences(String text) {
        return text
                .replaceFirst("(?i)^```(?:json)?\\s*\\n?", "")
                .replaceFirst("\\n?```\\s*$", "")
                .trim();
    }

    private static boolean looksLikeJson(String text) {
        return (text.startsWith("{") && text.endsWith("}"))
                || (text.startsWith("[") && text.endsWith("]"));
    }

    private static int matchingClose(String text, int start, char open, char close) {
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }
}
