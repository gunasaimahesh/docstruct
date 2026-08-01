package com.docstruct.util;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Locale;
import java.util.regex.Pattern;

import com.docstruct.domain.ColumnType;

/**
 * Format validation for extracted values.
 *
 * A value that fails validation is never dropped or "fixed" — it is reported so
 * the confidence score can be reduced (see {@link ConfidenceScorer}). Silently
 * accepting 2026-02-31 or "j.doe@@example" is how bad data reaches a database
 * looking exactly as trustworthy as good data.
 *
 * Column TYPE drives most checks. Where the schema is only "text" but the column
 * NAME says otherwise (phones and IDs have no dedicated type), a name heuristic
 * fills the gap.
 */
public final class FieldValidator {

    public enum Outcome {
        VALID,
        INVALID,
        /** Nothing meaningful to check — no value, free text, or a nested entity. */
        NOT_APPLICABLE
    }

    /** Strict so that impossible calendar dates (2026-02-31) are rejected, not shifted. */
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter
            .ofPattern("uuuu-MM-dd", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT);

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@.]+(\\.[^\\s@.]+)+$");
    /**
     * The scheme is optional: documents print "linkedin.com/in/jane" far more often
     * than they print "https://linkedin.com/in/jane", and demanding a scheme flags a
     * value that was transcribed exactly as written. A hostname with a real dot and
     * no whitespace is the actual requirement.
     */
    private static final Pattern URL = Pattern.compile(
            "^(https?://)?([\\w-]+\\.)+[a-z]{2,}(:\\d+)?([/?#]\\S*)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DIGITS = Pattern.compile("\\d");

    private static final Pattern PHONE_COLUMN =
            Pattern.compile("(?i).*(phone|mobile|cell|fax|telephone|\\btel\\b|whatsapp|contact[\\s_-]?(number|no|num|phone|mobile)).*");
    private static final Pattern EMAIL_COLUMN =
            Pattern.compile("(?i).*(e-?mail).*");
    private static final Pattern ID_COLUMN =
            Pattern.compile("(?i).*(\\bid\\b|_id|number|\\bno\\b|\\bnum\\b|reference|\\bref\\b|\\bcode\\b|sku|account).*");

    /** International numbers run 7–15 digits (E.164 caps at 15); extensions push the upper bound. */
    private static final int MIN_PHONE_DIGITS = 7;
    private static final int MAX_PHONE_DIGITS = 17;

    /** IDs are short tokens, not sentences. */
    private static final int MAX_ID_WORDS = 4;
    private static final int MAX_ID_CHARS = 64;

    private FieldValidator() {
    }

    public static Outcome validate(Object value, ColumnType type, String columnName) {
        if (value == null || type == ColumnType.ENTITY_ARRAY) {
            return Outcome.NOT_APPLICABLE;
        }

        return switch (type) {
            // The mapper coerces these before validation, so a leftover String
            // means the coercion failed and the value is not really numeric.
            case NUMBER, CURRENCY -> outcome(value instanceof Number);
            case BOOLEAN -> outcome(value instanceof Boolean);
            case DATE -> outcome(isIsoDate(value.toString()));
            case EMAIL -> outcome(EMAIL.matcher(value.toString().strip()).matches());
            case URL -> outcome(URL.matcher(value.toString().strip()).matches());
            default -> validateByColumnName(value.toString().strip(), columnName);
        };
    }

    /** Text columns whose name implies a format the schema has no type for. */
    private static Outcome validateByColumnName(String value, String columnName) {
        if (value.isEmpty() || columnName == null) {
            return Outcome.NOT_APPLICABLE;
        }
        if (EMAIL_COLUMN.matcher(columnName).matches()) {
            return outcome(EMAIL.matcher(value).matches());
        }
        if (PHONE_COLUMN.matcher(columnName).matches()) {
            return outcome(isPhone(value));
        }
        if (ID_COLUMN.matcher(columnName).matches()) {
            return outcome(isIdentifier(value));
        }
        return Outcome.NOT_APPLICABLE;
    }

    private static boolean isIsoDate(String value) {
        try {
            LocalDate.parse(value.strip(), ISO_DATE);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    /** Punctuation and country prefixes vary wildly; the digit count is the real constraint. */
    private static boolean isPhone(String value) {
        long digits = value.chars().filter(Character::isDigit).count();
        // Allow common unicode dashes (en/em) that PDFs and models use interchangeably with '-'.
        String normalized = value.replace('\u2013', '-').replace('\u2014', '-');
        return digits >= MIN_PHONE_DIGITS && digits <= MAX_PHONE_DIGITS
                && normalized.matches("[0-9+()\\-.\\s/extEXT]+");
    }

    /** A plausible identifier: a short token, no line breaks, containing at least one digit or letter run. */
    private static boolean isIdentifier(String value) {
        if (value.length() > MAX_ID_CHARS || value.contains("\n")) {
            return false;
        }
        if (value.split("\\s+").length > MAX_ID_WORDS) {
            return false;
        }
        return DIGITS.matcher(value).find() || value.chars().anyMatch(Character::isLetter);
    }

    private static Outcome outcome(boolean valid) {
        return valid ? Outcome.VALID : Outcome.INVALID;
    }
}
