package com.docstruct.util;

import java.util.List;
import java.util.Map;

import com.docstruct.domain.ConfidenceLevel;
import com.docstruct.domain.extraction.ExtractionCell;

/** Aggregates per-cell confidence levels into an overall document confidence. */
public final class ConfidenceCalculator {

    private ConfidenceCalculator() {
    }

    public static ConfidenceLevel overall(List<Map<String, ExtractionCell>> rows) {
        if (rows.isEmpty()) {
            return ConfidenceLevel.LOW;
        }

        int highCount = 0;
        int lowCount = 0;
        int total = 0;

        for (Map<String, ExtractionCell> row : rows) {
            for (ExtractionCell cell : row.values()) {
                total++;
                if (cell.confidence() == ConfidenceLevel.HIGH) {
                    highCount++;
                } else if (cell.confidence() == ConfidenceLevel.LOW) {
                    lowCount++;
                }
            }
        }

        if (total == 0) {
            return ConfidenceLevel.MEDIUM;
        }
        if ((double) highCount / total > 0.7) {
            return ConfidenceLevel.HIGH;
        }
        if ((double) lowCount / total > 0.3) {
            return ConfidenceLevel.LOW;
        }
        return ConfidenceLevel.MEDIUM;
    }
}
