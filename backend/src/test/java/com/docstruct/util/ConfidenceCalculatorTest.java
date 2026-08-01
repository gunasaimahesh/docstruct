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

    @Test
    void fieldsAbsentFromTheDocumentDoNotDragTheDocumentDown() {
        Map<String, ExtractionCell> row = row(ConfidenceLevel.HIGH, ConfidenceLevel.HIGH);
        row.put("absent1", ExtractionCell.of(null, ConfidenceLevel.LOW, ImportanceLevel.LOW));
        row.put("absent2", ExtractionCell.of(null, ConfidenceLevel.LOW, ImportanceLevel.LOW));
        row.put("absent3", ExtractionCell.of(null, ConfidenceLevel.LOW, ImportanceLevel.LOW));

        assertThat(ConfidenceCalculator.overall(List.of(row))).isEqualTo(ConfidenceLevel.HIGH);
    }

    @Test
    void anExtractionWithNoValuesAtAllIsLow() {
        Map<String, ExtractionCell> row = new java.util.LinkedHashMap<>();
        row.put("absent", ExtractionCell.of(null, ConfidenceLevel.LOW, ImportanceLevel.LOW));

        assertThat(ConfidenceCalculator.overall(List.of(row))).isEqualTo(ConfidenceLevel.LOW);
    }

    @Test
    void onlyExtractedValuesAreCountedForReview() {
        Map<String, ExtractionCell> row = row(ConfidenceLevel.LOW, ConfidenceLevel.HIGH);
        row.put("absent", ExtractionCell.of(null, ConfidenceLevel.LOW, ImportanceLevel.LOW));

        assertThat(ConfidenceCalculator.lowConfidenceValueCount(List.of(row))).isEqualTo(1);
    }

    @Test
    void overallConfidenceRecursesIntoEntityArrays() {
        // A resume-shaped row: a few HIGH section lists must not hide many LOW nested fields.
        Map<String, ExtractionCell> experienceRow = new java.util.LinkedHashMap<>();
        experienceRow.put("company", ExtractionCell.of("Acme", ConfidenceLevel.LOW, ImportanceLevel.MEDIUM));
        experienceRow.put("role", ExtractionCell.of("Engineer", ConfidenceLevel.LOW, ImportanceLevel.MEDIUM));
        experienceRow.put("dates", ExtractionCell.of("2024", ConfidenceLevel.LOW, ImportanceLevel.MEDIUM));

        Map<String, ExtractionCell> row = new java.util.LinkedHashMap<>();
        row.put("name", ExtractionCell.of("Jane", ConfidenceLevel.HIGH, ImportanceLevel.HIGH));
        row.put("Experience", ExtractionCell.of(
                List.of(experienceRow), ConfidenceLevel.HIGH, ImportanceLevel.HIGH));

        assertThat(ConfidenceCalculator.overall(List.of(row))).isEqualTo(ConfidenceLevel.LOW);
        assertThat(ConfidenceCalculator.lowConfidenceValueCount(List.of(row))).isEqualTo(3);
    }
}
