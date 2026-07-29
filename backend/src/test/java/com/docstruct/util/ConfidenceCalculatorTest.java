package com.docstruct.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.docstruct.domain.ConfidenceLevel;
import com.docstruct.domain.ImportanceLevel;
import com.docstruct.domain.extraction.ExtractionCell;

class ConfidenceCalculatorTest {

    private static Map<String, ExtractionCell> row(ConfidenceLevel... levels) {
        Map<String, ExtractionCell> row = new java.util.LinkedHashMap<>();
        for (int i = 0; i < levels.length; i++) {
            row.put("col" + i, ExtractionCell.of("v", levels[i], ImportanceLevel.MEDIUM));
        }
        return row;
    }

    @Test
    void emptyRowsAreLowConfidence() {
        assertThat(ConfidenceCalculator.overall(List.of())).isEqualTo(ConfidenceLevel.LOW);
    }

    @Test
    void mostlyHighCellsGiveHighOverall() {
        assertThat(ConfidenceCalculator.overall(List.of(
                row(ConfidenceLevel.HIGH, ConfidenceLevel.HIGH, ConfidenceLevel.HIGH, ConfidenceLevel.MEDIUM))))
                .isEqualTo(ConfidenceLevel.HIGH);
    }

    @Test
    void manyLowCellsGiveLowOverall() {
        assertThat(ConfidenceCalculator.overall(List.of(
                row(ConfidenceLevel.LOW, ConfidenceLevel.LOW, ConfidenceLevel.HIGH))))
                .isEqualTo(ConfidenceLevel.LOW);
    }

    @Test
    void mixedCellsGiveMediumOverall() {
        assertThat(ConfidenceCalculator.overall(List.of(
                row(ConfidenceLevel.HIGH, ConfidenceLevel.MEDIUM, ConfidenceLevel.MEDIUM))))
                .isEqualTo(ConfidenceLevel.MEDIUM);
    }
}
