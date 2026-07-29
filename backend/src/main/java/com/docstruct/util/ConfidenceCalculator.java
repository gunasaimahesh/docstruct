package com.docstruct.util;

import java.util.List;
import java.util.Map;

import com.docstruct.domain.ConfidenceLevel;
import com.docstruct.domain.extraction.ExtractionCell;

/** Aggregates per-cell confidence levels into an overall document confidence. */
public final class ConfidenceCalculator {

    private ConfidenceCalculator() {
    }

    /**
     * Confidence in the values that were actually extracted.
     *
     * Cells with no value are excluded: a field the document does not contain is a
     * fact about the document, not an uncertain extraction. Counting absences as
     * low confidence would make a correctly-reported gap look like a bad reading.
     */
    public static ConfidenceLevel overall(List<Map<String, ExtractionCell>> rows) {
        if (rows.isEmpty()) {
            return ConfidenceLevel.LOW;
        }

        int highCount = 0;
        int lowCount = 0;
        int total = 0;

        for (Map<String, ExtractionCell> row : rows) {
            for (ExtractionCell cell : row.values()) {
                if (cell.value() == null) {
                    continue;
                }
                total++;
                if (cell.confidence() == ConfidenceLevel.HIGH) {
                    highCount++;
                } else if (cell.confidence() == ConfidenceLevel.LOW) {
                    lowCount++;
                }
            }
        }

        if (total == 0) {
            // Rows exist but every value is absent: nothing was actually extracted.
            return ConfidenceLevel.LOW;
        }
        if ((double) highCount / total > 0.7) {
            return ConfidenceLevel.HIGH;
        }
        if ((double) lowCount / total > 0.3) {
            return ConfidenceLevel.LOW;
        }
        return ConfidenceLevel.MEDIUM;
    }

    /**
     * Counts extracted (non-null) values that came out of verification as low
     * confidence — the number a reviewer should actually look at. Nulls are
     * excluded: a field the document genuinely does not contain is not a defect.
     */
    public static int lowConfidenceValueCount(List<Map<String, ExtractionCell>> rows) {
        int count = 0;
        for (Map<String, ExtractionCell> row : rows) {
            for (ExtractionCell cell : row.values()) {
                count += countCell(cell);
            }
        }
        return count;
    }

    /** Recurses through entity_array cells so nested line items are counted too. */
    private static int countCell(ExtractionCell cell) {
        if (!(cell.value() instanceof List<?> nestedRows)) {
            return cell.value() != null && cell.confidence() == ConfidenceLevel.LOW ? 1 : 0;
        }

        int count = 0;
        for (Object nestedRow : nestedRows) {
            if (nestedRow instanceof Map<?, ?> map) {
                for (Object nestedCell : map.values()) {
                    if (nestedCell instanceof ExtractionCell child) {
                        count += countCell(child);
                    }
                }
            }
        }
        return count;
    }
}
