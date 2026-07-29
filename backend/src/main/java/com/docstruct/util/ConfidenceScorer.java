package com.docstruct.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.docstruct.domain.ColumnType;
import com.docstruct.domain.ConfidenceLevel;
import com.docstruct.domain.extraction.CellEvidence;
import com.docstruct.domain.extraction.DocumentChunk;
import com.docstruct.domain.extraction.ExtractionCell;
import com.docstruct.domain.schema.SchemaColumn;

/**
 * Turns an LLM-reported cell into a verified one: checks the citation against the
 * real document chunks, checks the value against the quoted source text, runs
 * format validation, and derives a deterministic 0–1 confidence score.
 *
 * Why not just trust the model's own "confidence"? Because the same model that
 * hallucinates a value also grades it — a fabricated invoice number arrives
 * labelled "high". Self-reported confidence is used here only as a penalty (a
 * model admitting doubt is informative), never as evidence that a value is real.
 *
 * Every rule is a fixed deduction from 1.0, so the same extraction always scores
 * the same. No model, no training, no randomness. Two findings additionally force
 * a Low rating whatever the arithmetic says: a value that appears nowhere in the
 * document, and a value that fails format validation.
 */
public final class ConfidenceScorer {

    // ---- Scoring rubric ----

    public static final double HIGH_THRESHOLD = 0.8;
    public static final double MEDIUM_THRESHOLD = 0.5;

    /** A value with no citation at all: unverifiable by construction. */
    private static final double PENALTY_NO_CITATION = 0.3;
    /** A citation pointing at a chunk that does not exist — a fabricated reference. */
    private static final double PENALTY_INVALID_CITATION = 0.5;
    /** The quoted source text appears nowhere in the document: the quote itself was invented. */
    private static final double PENALTY_SOURCE_ABSENT = 0.35;
    /** The quote is real but lives in a different chunk: a citation error, not an invention. */
    private static final double PENALTY_SOURCE_MISPLACED = 0.25;
    /** No verbatim quote supplied, so grounding falls back to the value itself. */
    private static final double PENALTY_NO_QUOTE = 0.15;
    /** The value cannot be found in the text it claims to come from. */
    private static final double PENALTY_VALUE_UNGROUNDED = 0.2;
    private static final double PENALTY_VALIDATION_FAILED = 0.3;
    /** Model-reported doubt. LOW caps the result at medium; MEDIUM still allows high if verification passes. */
    private static final double PENALTY_MODEL_MEDIUM = 0.1;
    private static final double PENALTY_MODEL_LOW = 0.3;

    /** Numeric equality tolerance — guards against float representation noise only. */
    private static final double NUMERIC_EPSILON = 0.005;

    /** How far a phrase's words may be spread out in the text and still count as found. */
    private static final int WINDOW_SLACK_FACTOR = 2;
    /** A single word is either in the text or it is not; windows only apply to phrases. */
    private static final int MIN_WINDOW_PHRASE_WORDS = 2;
    /** At this length a value is an assembly of several source lines rather than a quote. */
    private static final int AGGREGATED_PHRASE_WORDS = 6;

    private static final Pattern NUMBER_TOKEN = Pattern.compile("-?\\d+(?:\\.\\d+)?");
    private static final Pattern THOUSANDS_SEPARATOR = Pattern.compile("(?<=\\d),(?=\\d{3}(?!\\d))");

    // ---- Cross-field total checks ----

    private static final Pattern LINE_AMOUNT_COLUMN =
            Pattern.compile("(?i).*(amount|line[\\s_-]?total|extended|subtotal|total|price).*");
    private static final Pattern LINE_AMOUNT_EXCLUDED =
            Pattern.compile("(?i).*(unit|rate|per[\\s_-]|each|tax|qty|quantity|discount|hours).*");
    private static final Pattern SUBTOTAL_COLUMN =
            Pattern.compile("(?i).*(sub[\\s_-]?total|net[\\s_-]?(amount|total)).*");
    private static final Pattern TOTAL_COLUMN =
            Pattern.compile("(?i).*(grand[\\s_-]?total|total|amount[\\s_-]?due|balance[\\s_-]?due|amount[\\s_-]?payable).*");
    private static final Pattern SURCHARGE_COLUMN =
            Pattern.compile("(?i).*(tax|vat|gst|shipping|freight|handling|fee|surcharge|delivery).*");
    private static final Pattern DISCOUNT_COLUMN =
            Pattern.compile("(?i).*(discount|credit|rebate|adjustment).*");

    /** Relative tolerance for the line-items-vs-total check; rounding differences are not errors. */
    private static final double TOTAL_TOLERANCE_RATIO = 0.01;
    private static final double TOTAL_TOLERANCE_MIN = 0.02;

    // ---- State ----

    /** Normalized forms of one piece of text, precomputed once for cheap repeated matching. */
    private record Searchable(String text, List<String> words, Set<Double> numbers) {
    }

    private final Map<Integer, DocumentChunk> chunksByIndex;
    private final Map<Integer, Searchable> searchableChunks;
    private final Searchable wholeDocument;
    private final String unverifiableReason;

    private ConfidenceScorer(List<DocumentChunk> chunks, String unverifiableReason) {
        this.chunksByIndex = new LinkedHashMap<>();
        this.searchableChunks = new LinkedHashMap<>();
        StringBuilder all = new StringBuilder();

        for (DocumentChunk chunk : chunks) {
            chunksByIndex.put(chunk.index(), chunk);
            searchableChunks.put(chunk.index(), searchable(chunk.text()));
            all.append(chunk.text()).append('\n');
        }

        this.wholeDocument = searchable(all.toString());
        this.unverifiableReason = unverifiableReason;
    }

    /** Scorer for a document whose text is available: citations are fully verified. */
    public static ConfidenceScorer forChunks(List<DocumentChunk> chunks) {
        return new ConfidenceScorer(chunks, null);
    }

    /**
     * Scorer for a document with no extractable text (an image read by LLM vision).
     * Grounding checks are impossible, so they are skipped rather than faked, and
     * no value from such a document can reach "high" confidence.
     */
    public static ConfidenceScorer unverifiable(String reason) {
        return new ConfidenceScorer(List.of(), reason);
    }

    public boolean isVerifiable() {
        return unverifiableReason == null;
    }

    // ---- Per-cell scoring ----

    /** Returns the cell with its confidence replaced by the verified level plus evidence. */
    public ExtractionCell score(ExtractionCell cell, SchemaColumn column) {
        if (column.isEntityArray()) {
            // A repeating section has no single source location; its nested cells
            // are each scored individually as the rows are mapped.
            return cell;
        }
        if (cell.value() == null) {
            return cell.verified(ConfidenceLevel.LOW,
                    CellEvidence.note("No value for this field was found in the document"));
        }

        List<String> notes = new ArrayList<>();
        double score = 1.0;

        boolean validationFailed = FieldValidator.validate(cell.value(), column.type(), column.name())
                == FieldValidator.Outcome.INVALID;
        if (validationFailed) {
            score -= PENALTY_VALIDATION_FAILED;
            notes.add("Value failed %s validation".formatted(column.type().toJson()));
        }

        Integer page = cell.evidence() != null ? cell.evidence().page() : null;
        Integer chunk = cell.evidence() != null ? cell.evidence().chunk() : null;
        boolean valueLocated = true;

        if (isVerifiable()) {
            DocumentChunk cited = chunk != null ? chunksByIndex.get(chunk) : null;
            if (chunk == null) {
                score -= PENALTY_NO_CITATION;
                notes.add("No source chunk was cited for this value");
            } else if (cited == null) {
                score -= PENALTY_INVALID_CITATION;
                notes.add("Cited chunk %d does not exist in this document".formatted(chunk));
                chunk = null;
                page = null;
            } else {
                // The chunk index is the authority on which page the text is on;
                // a disagreeing page number is corrected rather than stored as-is.
                if (page == null || page != cited.page()) {
                    notes.add("Cited page corrected to %d from the cited chunk".formatted(cited.page()));
                }
                page = cited.page();
            }

            score -= groundingPenalty(cell, column.type(), cited, notes);

            valueLocated = valueAppearsIn(cell.value(), column.type(), wholeDocument);
            if (!valueLocated) {
                notes.add("Value does not appear anywhere in the document text");
            }
        } else {
            notes.add(unverifiableReason);
            page = null;
            chunk = null;
        }

        score -= modelDoubtPenalty(cell.confidence());

        double finalScore = clamp(score);
        ConfidenceLevel level = level(finalScore);
        if (!valueLocated || validationFailed) {
            // Unfindable or malformed values are always flagged for review, however
            // confident the model was and however well the rest of the cell checks out.
            level = ConfidenceLevel.LOW;
        } else if (!isVerifiable() && level == ConfidenceLevel.HIGH) {
            // Nothing was verified, so "high" would overstate what we actually know.
            level = ConfidenceLevel.MEDIUM;
        }

        return cell.verified(level, new CellEvidence(page, chunk, round(finalScore), joinNotes(notes)));
    }

    /**
     * Checks that the quoted source text really exists where the model says it does,
     * and that the extracted value is actually present in that quote. Whether the
     * value exists in the document at all is checked separately, by the caller,
     * because that outcome is decisive rather than a deduction.
     */
    private double groundingPenalty(ExtractionCell cell, ColumnType type,
                                    DocumentChunk cited, List<String> notes) {
        String quote = cell.rawSource() != null ? cell.rawSource().strip() : "";
        Searchable citedText = cited != null ? searchableChunks.get(cited.index()) : null;
        double penalty = 0;

        if (quote.isEmpty()) {
            penalty += PENALTY_NO_QUOTE;
            notes.add("No verbatim source quote was supplied");
            if (citedText != null && !valueAppearsIn(cell.value(), type, citedText)) {
                penalty += PENALTY_VALUE_UNGROUNDED;
                notes.add("Value does not appear in the cited chunk");
            }
            return penalty;
        }

        boolean inCitedChunk = citedText != null && phraseAppearsIn(quote, citedText);
        if (!inCitedChunk) {
            if (phraseAppearsIn(quote, wholeDocument)) {
                penalty += PENALTY_SOURCE_MISPLACED;
                notes.add("Quoted source text was found elsewhere in the document, not in the cited chunk");
            } else {
                penalty += PENALTY_SOURCE_ABSENT;
                notes.add("Quoted source text does not appear anywhere in the document");
            }
        }

        if (!valueAppearsIn(cell.value(), type, searchable(quote))) {
            penalty += PENALTY_VALUE_UNGROUNDED;
            notes.add("Value does not appear in the quoted source text");
        }
        return penalty;
    }

    /**
     * Is the value present in this text? Dates and booleans are exempt: ISO dates
     * and true/false are deliberate normalizations of wording like "March 14, 2026"
     * or "Paid", so demanding a literal match would punish correct behavior.
     */
    private boolean valueAppearsIn(Object value, ColumnType type, Searchable haystack) {
        if (type == ColumnType.DATE || type == ColumnType.BOOLEAN || value instanceof Boolean) {
            return true;
        }
        if (value instanceof Number number) {
            return haystack.numbers().stream()
                    .anyMatch(candidate -> Math.abs(candidate - number.doubleValue()) < NUMERIC_EPSILON);
        }
        return phraseAppearsIn(value.toString(), haystack);
    }

    /**
     * Is this phrase present in the text? A contiguous match is the normal case.
     * Failing that, the phrase counts as found when all of its words sit inside a
     * short window of the text. Multi-column PDFs are extracted row by row, so a
     * phrase read correctly out of one column arrives with the other column's words
     * spliced into it — a two-column resume yields "Software Engineer B.Tech in
     * Computer / Enphase Energy Science", where "B.Tech in Computer Science" is
     * never contiguous. The window is capped at twice the phrase length, so an
     * invented phrase, whose words never co-occur anywhere, is still rejected.
     *
     * Long values get no proximity requirement at all, only full word coverage: a
     * skills list or a multi-line achievement is the model assembling a column grid
     * into one string, so no contiguous source for it exists to be found. Coverage
     * still catches invented content — a skill the document never mentions leaves a
     * word that appears nowhere — but it cannot detect reordering within the value.
     */
    private static boolean phraseAppearsIn(String phrase, Searchable haystack) {
        String normalized = normalizeText(phrase);
        if (normalized.isEmpty() || haystack.text().contains(normalized)) {
            return true;
        }

        List<String> needle = words(normalized);
        if (needle.size() < MIN_WINDOW_PHRASE_WORDS) {
            return false;
        }

        int window = shortestWindowContaining(needle, haystack.words());
        if (needle.size() >= AGGREGATED_PHRASE_WORDS) {
            return window != Integer.MAX_VALUE;
        }
        return window <= needle.size() * WINDOW_SLACK_FACTOR;
    }

    /**
     * Length, in words, of the tightest span of the haystack that contains every
     * needle word. {@link Integer#MAX_VALUE} when some word is missing entirely.
     */
    private static int shortestWindowContaining(List<String> needle, List<String> haystack) {
        Map<String, Integer> required = new LinkedHashMap<>();
        needle.forEach(word -> required.merge(word, 1, Integer::sum));

        Map<String, Integer> seen = new LinkedHashMap<>();
        int satisfied = 0;
        int left = 0;
        int shortest = Integer.MAX_VALUE;

        for (int right = 0; right < haystack.size(); right++) {
            String entering = haystack.get(right);
            Integer need = required.get(entering);
            if (need != null && seen.merge(entering, 1, Integer::sum).equals(need)) {
                satisfied++;
            }
            // Shrink from the left while the window is still complete: the tightest
            // span ending at this word is what tells us how spread out the phrase is.
            while (satisfied == required.size()) {
                shortest = Math.min(shortest, right - left + 1);
                String leaving = haystack.get(left++);
                Integer stillNeeded = required.get(leaving);
                if (stillNeeded != null && seen.merge(leaving, -1, Integer::sum) < stillNeeded) {
                    satisfied--;
                }
            }
        }
        return shortest;
    }

    private static double modelDoubtPenalty(ConfidenceLevel reported) {
        if (reported == ConfidenceLevel.LOW) {
            return PENALTY_MODEL_LOW;
        }
        return reported == ConfidenceLevel.MEDIUM ? PENALTY_MODEL_MEDIUM : 0;
    }

    // ---- Cross-field checks ----

    /**
     * Arithmetic consistency check: do the row's line items add up to its stated total?
     * A hallucinated or misread total is the single most damaging error in invoice
     * extraction and it is one of the few things a document can be checked against
     * itself for. On mismatch the total is downgraded — never silently corrected,
     * because we cannot know which of the two numbers is the wrong one.
     */
    public void crossCheckTotals(Map<String, ExtractionCell> row, List<SchemaColumn> columns) {
        Double lineSum = sumLineItems(row, columns);
        if (lineSum == null) {
            return;
        }

        SchemaColumn subtotalColumn = findColumn(columns, SUBTOTAL_COLUMN, null);
        SchemaColumn target = subtotalColumn != null
                ? subtotalColumn
                : findColumn(columns, TOTAL_COLUMN, SUBTOTAL_COLUMN);
        if (target == null) {
            return;
        }

        Double stated = numericValue(row.get(target.name()));
        if (stated == null) {
            return;
        }

        // A grand total legitimately includes tax and shipping; a subtotal does not.
        double expected = lineSum;
        if (subtotalColumn == null) {
            expected += sumMatching(row, columns, SURCHARGE_COLUMN) - sumMatching(row, columns, DISCOUNT_COLUMN);
        }

        double tolerance = Math.max(TOTAL_TOLERANCE_MIN, Math.abs(stated) * TOTAL_TOLERANCE_RATIO);
        if (Math.abs(expected - stated) <= tolerance) {
            return;
        }

        ExtractionCell cell = row.get(target.name());
        String note = "Line items add up to %s but the document states %s".formatted(
                trimNumber(expected), trimNumber(stated));
        CellEvidence previous = cell.evidence();
        double reduced = previous != null && previous.score() != null
                ? clamp(previous.score() - PENALTY_VALIDATION_FAILED)
                : clamp(1.0 - PENALTY_VALIDATION_FAILED);

        row.put(target.name(), cell.verified(
                ConfidenceLevel.LOW,
                new CellEvidence(
                        previous != null ? previous.page() : null,
                        previous != null ? previous.chunk() : null,
                        round(reduced),
                        joinNotes(previous != null && previous.note() != null
                                ? List.of(previous.note(), note)
                                : List.of(note)))));
    }

    /** Sums the amount column of the first entity_array that has one. */
    private Double sumLineItems(Map<String, ExtractionCell> row, List<SchemaColumn> columns) {
        for (SchemaColumn column : columns) {
            if (!column.isEntityArray() || column.entitySchema() == null) {
                continue;
            }
            SchemaColumn amountColumn = findColumn(
                    column.entitySchema().columns(), LINE_AMOUNT_COLUMN, LINE_AMOUNT_EXCLUDED);
            if (amountColumn == null) {
                continue;
            }
            ExtractionCell arrayCell = row.get(column.name());
            if (arrayCell == null || !(arrayCell.value() instanceof List<?> childRows)) {
                continue;
            }

            double sum = 0;
            int counted = 0;
            for (Object child : childRows) {
                if (child instanceof Map<?, ?> childRow) {
                    Double amount = numericValue(childRow.get(amountColumn.name()));
                    if (amount != null) {
                        sum += amount;
                        counted++;
                    }
                }
            }
            if (counted > 0) {
                return sum;
            }
        }
        return null;
    }

    private double sumMatching(Map<String, ExtractionCell> row, List<SchemaColumn> columns, Pattern pattern) {
        double sum = 0;
        for (SchemaColumn column : columns) {
            if (isNumeric(column) && pattern.matcher(column.name()).matches()) {
                Double value = numericValue(row.get(column.name()));
                if (value != null) {
                    sum += Math.abs(value);
                }
            }
        }
        return sum;
    }

    private static SchemaColumn findColumn(List<SchemaColumn> columns, Pattern include, Pattern exclude) {
        return columns.stream()
                .filter(ConfidenceScorer::isNumeric)
                .filter(column -> include.matcher(column.name()).matches())
                .filter(column -> exclude == null || !exclude.matcher(column.name()).matches())
                .findFirst()
                .orElse(null);
    }

    private static boolean isNumeric(SchemaColumn column) {
        return column.type() == ColumnType.CURRENCY || column.type() == ColumnType.NUMBER;
    }

    private static Double numericValue(Object cell) {
        if (cell instanceof ExtractionCell extractionCell && extractionCell.value() instanceof Number number) {
            return number.doubleValue();
        }
        return null;
    }

    // ---- Text normalization ----

    private static Searchable searchable(String text) {
        String normalized = normalizeText(text);
        return new Searchable(normalized, words(normalized), numbersIn(text));
    }

    private static List<String> words(String normalizedText) {
        return normalizedText.isEmpty() ? List.of() : List.of(normalizedText.split(" "));
    }

    /**
     * Case, punctuation and whitespace differences between a quote and the source
     * are not hallucinations. Reducing both sides to lowercase alphanumeric words
     * makes containment checks robust without making them meaningless.
     */
    static String normalizeText(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").strip();
    }

    /** Every number in the text, with thousands separators removed so 1,234.50 matches 1234.5. */
    static Set<Double> numbersIn(String text) {
        Set<Double> numbers = new LinkedHashSet<>();
        Matcher matcher = NUMBER_TOKEN.matcher(THOUSANDS_SEPARATOR.matcher(text).replaceAll(""));
        while (matcher.find()) {
            try {
                numbers.add(Double.valueOf(matcher.group()));
            } catch (NumberFormatException e) {
                // Not a representable number; nothing to match against.
            }
        }
        return numbers;
    }

    // ---- Small helpers ----

    static ConfidenceLevel level(double score) {
        if (score >= HIGH_THRESHOLD) {
            return ConfidenceLevel.HIGH;
        }
        return score >= MEDIUM_THRESHOLD ? ConfidenceLevel.MEDIUM : ConfidenceLevel.LOW;
    }

    private static double clamp(double score) {
        return Math.max(0, Math.min(1, score));
    }

    private static double round(double score) {
        return Math.round(score * 100) / 100.0;
    }

    private static String joinNotes(List<String> notes) {
        return notes.isEmpty() ? null : String.join("; ", notes);
    }

    private static String trimNumber(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(round(value));
    }
}
