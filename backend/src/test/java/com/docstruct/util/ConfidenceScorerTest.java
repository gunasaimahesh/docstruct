package com.docstruct.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.docstruct.domain.ColumnType;
import com.docstruct.domain.ConfidenceLevel;
import com.docstruct.domain.ImportanceLevel;
import com.docstruct.domain.extraction.CellEvidence;
import com.docstruct.domain.extraction.DocumentChunk;
import com.docstruct.domain.extraction.ExtractionCell;
import com.docstruct.domain.schema.EntitySchema;
import com.docstruct.domain.schema.SchemaColumn;

class ConfidenceScorerTest {

    private static final List<DocumentChunk> CHUNKS = List.of(
            new DocumentChunk(1, 1, "INVOICE\nInvoice Number: INV-2041\nDate: 2026-03-14"),
            new DocumentChunk(2, 3, "Vendor: Acme Corp\nEmail: billing@acme.com\nTotal Due: $1,234.50"));

    private static final SchemaColumn TEXT_COLUMN = new SchemaColumn("Vendor", ColumnType.TEXT, null, true);
    private static final SchemaColumn ID_COLUMN = new SchemaColumn("Invoice Number", ColumnType.TEXT, null, true);
    private static final SchemaColumn DATE_COLUMN = new SchemaColumn("Date", ColumnType.DATE, null, true);
    private static final SchemaColumn TOTAL_COLUMN = new SchemaColumn("Total Due", ColumnType.CURRENCY, null, true);

    private final ConfidenceScorer scorer = ConfidenceScorer.forChunks(CHUNKS);

    private static ExtractionCell cell(Object value, ConfidenceLevel reported,
                                       Integer page, Integer chunk, String rawSource) {
        return new ExtractionCell(value, reported, ImportanceLevel.MEDIUM, true, rawSource,
                page == null && chunk == null ? null : new CellEvidence(page, chunk, null, null));
    }

    // ---- Grounding ----

    @Test
    void correctlyCitedValueScoresHigh() {
        ExtractionCell scored = scorer.score(
                cell("Acme Corp", ConfidenceLevel.HIGH, 3, 2, "Vendor: Acme Corp"), TEXT_COLUMN);

        assertThat(scored.confidence()).isEqualTo(ConfidenceLevel.HIGH);
        assertThat(scored.evidence().score()).isEqualTo(1.0);
        assertThat(scored.evidence().page()).isEqualTo(3);
        assertThat(scored.evidence().chunk()).isEqualTo(2);
        assertThat(scored.evidence().note()).isNull();
    }

    @Test
    void fabricatedValueIsAlwaysLowEvenWhenTheModelIsConfident() {
        ExtractionCell scored = scorer.score(
                cell("Globex Industries", ConfidenceLevel.HIGH, 3, 2, "Vendor: Globex Industries"), TEXT_COLUMN);

        assertThat(scored.confidence()).isEqualTo(ConfidenceLevel.LOW);
        assertThat(scored.evidence().note())
                .contains("Quoted source text does not appear anywhere in the document")
                .contains("Value does not appear anywhere in the document text");
    }

    @Test
    void realValueWithAWrongCitationIsDowngradedNotRejected() {
        // "Acme Corp" is genuinely in the document, but in chunk 2, not chunk 1.
        ExtractionCell scored = scorer.score(
                cell("Acme Corp", ConfidenceLevel.HIGH, 1, 1, "Vendor: Acme Corp"), TEXT_COLUMN);

        assertThat(scored.confidence()).isEqualTo(ConfidenceLevel.MEDIUM);
        assertThat(scored.evidence().note()).contains("found elsewhere in the document");
    }

    @Test
    void citationToAChunkThatDoesNotExistIsDiscarded() {
        ExtractionCell scored = scorer.score(
                cell("Acme Corp", ConfidenceLevel.HIGH, 9, 99, "Vendor: Acme Corp"), TEXT_COLUMN);

        assertThat(scored.confidence()).isNotEqualTo(ConfidenceLevel.HIGH);
        assertThat(scored.evidence().chunk()).isNull();
        assertThat(scored.evidence().page()).isNull();
        assertThat(scored.evidence().note()).contains("Cited chunk 99 does not exist");
    }

    @Test
    void pageIsTakenFromTheCitedChunkNotTheModel() {
        ExtractionCell scored = scorer.score(
                cell("Acme Corp", ConfidenceLevel.HIGH, 7, 2, "Vendor: Acme Corp"), TEXT_COLUMN);

        assertThat(scored.evidence().page()).isEqualTo(3);
        assertThat(scored.evidence().note()).contains("Cited page corrected to 3");
    }

    @Test
    void uncitedButGroundedValueRecoversItsChunkAndCanScoreHigh() {
        // Nested entity_array fields often omit page/chunk/raw_source. If the value
        // is in the document, recover the citation rather than forcing Low.
        ExtractionCell scored = scorer.score(
                cell("Acme Corp", ConfidenceLevel.HIGH, null, null, null), TEXT_COLUMN);

        assertThat(scored.confidence()).isEqualTo(ConfidenceLevel.HIGH);
        assertThat(scored.evidence().chunk()).isEqualTo(2);
        assertThat(scored.evidence().page()).isEqualTo(3);
        assertThat(scored.evidence().note()).contains("Source chunk recovered");
    }

    @Test
    void uncitedInventedValueIsStillLow() {
        ExtractionCell scored = scorer.score(
                cell("Globex Industries", ConfidenceLevel.HIGH, null, null, null), TEXT_COLUMN);

        assertThat(scored.confidence()).isEqualTo(ConfidenceLevel.LOW);
        assertThat(scored.evidence().note())
                .contains("No source chunk was cited")
                .contains("Value does not appear anywhere in the document text");
    }

    @Test
    void phoneFormattingDifferencesDoNotFailGrounding() {
        ConfidenceScorer phones = ConfidenceScorer.forChunks(List.of(
                new DocumentChunk(1, 1, "Call +918919584215 or email me")));

        ExtractionCell scored = phones.score(
                cell("+91 8919584215", ConfidenceLevel.HIGH, 1, 1, "+91 8919584215"),
                new SchemaColumn("Phone", ColumnType.TEXT, null, true));

        assertThat(scored.confidence()).isEqualTo(ConfidenceLevel.HIGH);
    }

    @Test
    void phraseSplitByAMultiColumnLayoutStillCountsAsGrounded() {
        // How PDFBox extracts a two-column resume: the right column's education entry
        // is interleaved with the left column's job history, row by row.
        ConfidenceScorer twoColumn = ConfidenceScorer.forChunks(List.of(new DocumentChunk(1, 1, """
                EDUCATION
                Software Engineer B.Tech in Computer
                Enphase Energy Science GPA
                07/2024 - Present  India IIT Roorkee 8.27 / 10.00""")));

        ExtractionCell scored = twoColumn.score(
                cell("B.Tech in Computer Science", ConfidenceLevel.HIGH, 1, 1, "B.Tech in Computer Science"),
                new SchemaColumn("Degree", ColumnType.TEXT, null, true));

        assertThat(scored.confidence()).isEqualTo(ConfidenceLevel.HIGH);
        assertThat(scored.evidence().note()).isNull();
    }

    @Test
    void wordsScatteredAcrossTheDocumentDoNotCountAsGrounded() {
        // Every word of "Acme Industries" is present, but nowhere near each other:
        // the window is what stops the loosened match from accepting anything.
        ConfidenceScorer scattered = ConfidenceScorer.forChunks(List.of(new DocumentChunk(1, 1,
                "Vendor: Acme Corp\n" + "unrelated ".repeat(20) + "\nThe industries served include retail")));

        ExtractionCell scored = scattered.score(
                cell("Acme Industries", ConfidenceLevel.HIGH, 1, 1, "Vendor: Acme Industries"), TEXT_COLUMN);

        assertThat(scored.confidence()).isEqualTo(ConfidenceLevel.LOW);
        assertThat(scored.evidence().note()).contains("Value does not appear anywhere in the document text");
    }

    // A two-column skills grid: the model reads it as one list, the extractor
    // interleaves it with whatever sits in the neighbouring column.
    private static final ConfidenceScorer GRID = ConfidenceScorer.forChunks(List.of(new DocumentChunk(1, 1, """
            SUMMARY                             SKILLS
            Backend engineer with two years     Java Spring Boot Go Kafka
            of experience building and          MongoDB Redis AWS Lambda
            scaling distributed systems.        Multi-Threading Microservices""")));

    private static final SchemaColumn SKILLS_COLUMN = new SchemaColumn("Skills", ColumnType.TEXT, null, false);

    @Test
    void aggregatedValueIsGroundedWhenEveryWordIsInTheDocument() {
        String skills = "Java Spring Boot Go Kafka MongoDB Redis AWS Lambda";

        ExtractionCell scored = GRID.score(cell(skills, ConfidenceLevel.HIGH, 1, 1, skills), SKILLS_COLUMN);

        assertThat(scored.confidence()).isEqualTo(ConfidenceLevel.HIGH);
        assertThat(scored.evidence().note()).isNull();
    }

    @Test
    void aggregatedValueWithAnInventedItemIsStillFlagged() {
        String skills = "Java Spring Boot Go Kafka MongoDB Redis Rust Kubernetes";

        ExtractionCell scored = GRID.score(cell(skills, ConfidenceLevel.HIGH, 1, 1, skills), SKILLS_COLUMN);

        assertThat(scored.confidence()).isEqualTo(ConfidenceLevel.LOW);
        assertThat(scored.evidence().note()).contains("Value does not appear anywhere in the document text");
    }

    @Test
    void currencyFormattingDifferencesAreNotTreatedAsHallucinations() {
        ExtractionCell scored = scorer.score(
                cell(1234.5, ConfidenceLevel.HIGH, 3, 2, "Total Due: $1,234.50"), TOTAL_COLUMN);

        assertThat(scored.confidence()).isEqualTo(ConfidenceLevel.HIGH);
    }

    @Test
    void lakhAndCroreGroupingIsReadAsOneNumber() {
        ConfidenceScorer indianGrouping = ConfidenceScorer.forChunks(
                List.of(new DocumentChunk(1, 1, "Total Income 1A 31,48,250\nTaxes Paid 7 7,16,327")));

        ExtractionCell income = indianGrouping.score(
                cell(3148250.0, ConfidenceLevel.HIGH, 1, 1, "Total Income 1A 31,48,250"), TOTAL_COLUMN);
        ExtractionCell taxes = indianGrouping.score(
                cell(716327.0, ConfidenceLevel.HIGH, 1, 1, "Taxes Paid 7 7,16,327"), TOTAL_COLUMN);

        assertThat(income.confidence()).isEqualTo(ConfidenceLevel.HIGH);
        assertThat(income.evidence().note()).isNull();
        assertThat(taxes.confidence()).isEqualTo(ConfidenceLevel.HIGH);
        assertThat(taxes.evidence().note()).isNull();
    }

    @Test
    void aRefundMarkedNegativeInTheLabelGroundsASignedValue() {
        ConfidenceScorer refund = ConfidenceScorer.forChunks(List.of(new DocumentChunk(1, 1,
                "(+) Tax Payable /(-) Refundable (6-7) 8 (-) 1,70,870")));
        String quote = "(+) Tax Payable /(-) Refundable (6-7) 8 (-) 1,70,870";

        ExtractionCell signed = refund.score(cell(-170870.0, ConfidenceLevel.HIGH, 1, 1, quote), TOTAL_COLUMN);
        ExtractionCell unsigned = refund.score(cell(170870.0, ConfidenceLevel.HIGH, 1, 1, quote), TOTAL_COLUMN);

        assertThat(signed.confidence()).isEqualTo(ConfidenceLevel.HIGH);
        assertThat(signed.evidence().note()).isNull();
        assertThat(unsigned.confidence()).isEqualTo(ConfidenceLevel.HIGH);
    }

    @Test
    void accountingParenthesesMarkAFigureNegativeButALineReferenceDoesNot() {
        ConfidenceScorer bracketed = ConfidenceScorer.forChunks(
                List.of(new DocumentChunk(1, 1, "Balance (1,70,870)\nTax Payable (6-7) 8")));

        ExtractionCell bracketedFigure = bracketed.score(
                cell(-170870.0, ConfidenceLevel.HIGH, 1, 1, "Balance (1,70,870)"), TOTAL_COLUMN);
        ExtractionCell lineReference = bracketed.score(
                cell(-6.0, ConfidenceLevel.HIGH, 1, 1, "Tax Payable (6-7) 8"), TOTAL_COLUMN);

        assertThat(bracketedFigure.confidence()).isEqualTo(ConfidenceLevel.HIGH);
        assertThat(lineReference.confidence()).isEqualTo(ConfidenceLevel.LOW);
        assertThat(lineReference.evidence().note()).contains("Value does not appear anywhere in the document text");
    }

    @Test
    void aCommaSeparatedListIsNotWeldedIntoANumberThatIsNotThere() {
        ConfidenceScorer list = ConfidenceScorer.forChunks(
                List.of(new DocumentChunk(1, 1, "Quantities ordered: 1,2,3")));

        ExtractionCell scored = list.score(
                cell(123.0, ConfidenceLevel.HIGH, 1, 1, "Quantities ordered: 1,2,3"), TOTAL_COLUMN);

        assertThat(scored.confidence()).isEqualTo(ConfidenceLevel.LOW);
        assertThat(scored.evidence().note()).contains("Value does not appear anywhere in the document text");
    }

    @Test
    void isoDateNormalizationIsNotPenalized() {
        ConfidenceScorer wordyDate = ConfidenceScorer.forChunks(
                List.of(new DocumentChunk(1, 1, "Issued on March 14, 2026")));

        ExtractionCell scored = wordyDate.score(
                cell("2026-03-14", ConfidenceLevel.HIGH, 1, 1, "Issued on March 14, 2026"), DATE_COLUMN);

        assertThat(scored.confidence()).isEqualTo(ConfidenceLevel.HIGH);
    }

    @Test
    void modelReportedDoubtLowersButDoesNotDecideTheScore() {
        ExtractionCell confident = scorer.score(
                cell("Acme Corp", ConfidenceLevel.HIGH, 3, 2, "Vendor: Acme Corp"), TEXT_COLUMN);
        ExtractionCell doubtful = scorer.score(
                cell("Acme Corp", ConfidenceLevel.LOW, 3, 2, "Vendor: Acme Corp"), TEXT_COLUMN);

        assertThat(confident.evidence().score()).isGreaterThan(doubtful.evidence().score());
        assertThat(doubtful.confidence()).isEqualTo(ConfidenceLevel.MEDIUM);
    }

    // ---- Absent values and validation ----

    @Test
    void absentValueIsLowWithAnExplanationInsteadOfAGuess() {
        ExtractionCell scored = scorer.score(
                cell(null, ConfidenceLevel.HIGH, null, null, null), TEXT_COLUMN);

        assertThat(scored.value()).isNull();
        assertThat(scored.confidence()).isEqualTo(ConfidenceLevel.LOW);
        assertThat(scored.evidence().note()).isEqualTo("No value for this field was found in the document");
        assertThat(scored.evidence().score()).isNull();
    }

    @Test
    void impossibleDateFailsValidationAndIsForcedLow() {
        ConfidenceScorer badDate = ConfidenceScorer.forChunks(
                List.of(new DocumentChunk(1, 1, "Due Date: 2026-02-31")));

        ExtractionCell scored = badDate.score(
                cell("2026-02-31", ConfidenceLevel.HIGH, 1, 1, "Due Date: 2026-02-31"), DATE_COLUMN);

        assertThat(scored.confidence()).isEqualTo(ConfidenceLevel.LOW);
        assertThat(scored.evidence().note()).contains("failed date validation");
    }

    @Test
    void malformedIdIsForcedLow() {
        ConfidenceScorer longText = ConfidenceScorer.forChunks(List.of(new DocumentChunk(1, 1,
                "Invoice Number: this is clearly a sentence and not an invoice identifier at all")));

        ExtractionCell scored = longText.score(
                cell("this is clearly a sentence and not an invoice identifier at all",
                        ConfidenceLevel.HIGH, 1, 1,
                        "Invoice Number: this is clearly a sentence and not an invoice identifier at all"),
                ID_COLUMN);

        assertThat(scored.confidence()).isEqualTo(ConfidenceLevel.LOW);
    }

    // ---- Image documents ----

    @Test
    void imageDocumentsCannotReachHighConfidence() {
        ConfidenceScorer image = ConfidenceScorer.unverifiable("Image document — not verifiable");

        ExtractionCell scored = image.score(
                cell("Acme Corp", ConfidenceLevel.HIGH, 1, null, "Vendor: Acme Corp"), TEXT_COLUMN);

        assertThat(image.isVerifiable()).isFalse();
        assertThat(scored.confidence()).isEqualTo(ConfidenceLevel.MEDIUM);
        assertThat(scored.evidence().note()).isEqualTo("Image document — not verifiable");
        assertThat(scored.evidence().chunk()).isNull();
    }

    // ---- Cross-field total check ----

    private static final List<SchemaColumn> INVOICE_COLUMNS = List.of(
            new SchemaColumn("Total", ColumnType.CURRENCY, null, true),
            new SchemaColumn("Tax", ColumnType.CURRENCY, null, false),
            new SchemaColumn("Line Items", ColumnType.ENTITY_ARRAY, null, false,
                    new EntitySchema("Line Items", null, List.of(
                            new SchemaColumn("Description", ColumnType.TEXT, null, true),
                            new SchemaColumn("Amount", ColumnType.CURRENCY, null, true)))));

    private static Map<String, ExtractionCell> invoiceRow(double total, double tax, double... lineAmounts) {
        List<Map<String, ExtractionCell>> lineItems = new java.util.ArrayList<>();
        for (double amount : lineAmounts) {
            Map<String, ExtractionCell> line = new LinkedHashMap<>();
            line.put("Description", ExtractionCell.of("Item", ConfidenceLevel.HIGH, ImportanceLevel.MEDIUM));
            line.put("Amount", ExtractionCell.of(amount, ConfidenceLevel.HIGH, ImportanceLevel.MEDIUM));
            lineItems.add(line);
        }

        Map<String, ExtractionCell> row = new LinkedHashMap<>();
        row.put("Total", new ExtractionCell(total, ConfidenceLevel.HIGH, ImportanceLevel.HIGH, true,
                "Total: " + total, new CellEvidence(1, 1, 1.0, null)));
        row.put("Tax", ExtractionCell.of(tax, ConfidenceLevel.HIGH, ImportanceLevel.MEDIUM));
        row.put("Line Items", ExtractionCell.of(lineItems, ConfidenceLevel.HIGH, ImportanceLevel.HIGH));
        return row;
    }

    @Test
    void totalThatMatchesLineItemsPlusTaxIsLeftAlone() {
        Map<String, ExtractionCell> row = invoiceRow(110.0, 10.0, 60.0, 40.0);

        scorer.crossCheckTotals(row, INVOICE_COLUMNS);

        assertThat(row.get("Total").confidence()).isEqualTo(ConfidenceLevel.HIGH);
    }

    @Test
    void totalThatContradictsLineItemsIsDowngraded() {
        Map<String, ExtractionCell> row = invoiceRow(500.0, 10.0, 60.0, 40.0);

        scorer.crossCheckTotals(row, INVOICE_COLUMNS);

        assertThat(row.get("Total").confidence()).isEqualTo(ConfidenceLevel.LOW);
        assertThat(row.get("Total").value()).isEqualTo(500.0); // never silently corrected
        assertThat(row.get("Total").evidence().note()).contains("Line items add up to 110 but the document states 500");
        assertThat(row.get("Total").evidence().page()).isEqualTo(1); // citation is preserved
    }

    @Test
    void roundingDifferencesAreToleratedInTotals() {
        Map<String, ExtractionCell> row = invoiceRow(110.01, 10.0, 60.0, 40.0);

        scorer.crossCheckTotals(row, INVOICE_COLUMNS);

        assertThat(row.get("Total").confidence()).isEqualTo(ConfidenceLevel.HIGH);
    }
}
