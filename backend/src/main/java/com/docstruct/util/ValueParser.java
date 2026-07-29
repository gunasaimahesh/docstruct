package com.docstruct.util;

/**
 * Lenient parsing of user- or LLM-provided values into SQL-friendly types.
 * Both parsers return null when the input is not recognizable; callers decide
 * whether that is an error (user edits) or a soft miss (LLM extraction).
 */
public final class ValueParser {

    private static final String CURRENCY_NOISE = "[,$€£¥₹\\s]";

    private ValueParser() {
    }

    /** Parses a number, tolerating currency symbols and thousands separators. */
    public static Double parseNumber(String raw) {
        try {
            return Double.parseDouble(raw.replaceAll(CURRENCY_NOISE, ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Parses common boolean spellings (true/false, yes/no, 1/0, y/n). */
    public static Boolean parseBoolean(String raw) {
        return switch (raw.toLowerCase()) {
            case "true", "yes", "1", "y" -> Boolean.TRUE;
            case "false", "no", "0", "n" -> Boolean.FALSE;
            default -> null;
        };
    }
}
