package com.docstruct.parser;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.docstruct.domain.extraction.DocumentChunk;

class ChunkerTest {

    @Test
    void chunkIndexesAreSequentialAndPagesArePreserved() {
        List<DocumentChunk> chunks = Chunker.chunkPages(List.of(
                "Page one content", "Page two content", "Page three content"));

        assertThat(chunks).extracting(DocumentChunk::index).containsExactly(1, 2, 3);
        assertThat(chunks).extracting(DocumentChunk::page).containsExactly(1, 2, 3);
        assertThat(chunks.get(1).text()).isEqualTo("Page two content");
    }

    @Test
    void blankPagesAreSkippedWithoutShiftingLaterPageNumbers() {
        List<DocumentChunk> chunks = Chunker.chunkPages(List.of("First", "   ", "Third"));

        assertThat(chunks).hasSize(2);
        assertThat(chunks).extracting(DocumentChunk::page).containsExactly(1, 3);
    }

    @Test
    void aChunkNeverSpansTwoPages() {
        List<DocumentChunk> chunks = Chunker.chunkPages(List.of("short", "also short"));

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).text()).doesNotContain("also short");
    }

    @Test
    void longPagesAreSplitOnParagraphBoundaries() {
        String paragraph = "x".repeat(500);
        String page = String.join("\n\n", paragraph, paragraph, paragraph, paragraph);

        List<DocumentChunk> chunks = Chunker.chunkPages(List.of(page));

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.page()).isEqualTo(1));
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(chunk.text()).doesNotStartWith("\n").doesNotEndWith("\n"));
        // No content is lost by chunking
        assertThat(chunks.stream().mapToInt(chunk -> chunk.text().replace("\n", "").length()).sum())
                .isEqualTo(2000);
    }

    @Test
    void everyDelimitedChunkRepeatsTheHeaderRow() {
        StringBuilder csv = new StringBuilder("vendor,amount,description");
        for (int row = 0; row < 200; row++) {
            csv.append("\nAcme %d,%d,a reasonably long description of the purchased item".formatted(row, row));
        }

        List<DocumentChunk> chunks = Chunker.chunkDelimited(csv.toString());

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(chunk.text()).startsWith("vendor,amount,description"));
        assertThat(chunks).extracting(DocumentChunk::index).doesNotHaveDuplicates();
    }

    @Test
    void aSingleLineDocumentStillProducesOneChunk() {
        assertThat(Chunker.chunkDelimited("just,one,line")).hasSize(1);
        assertThat(Chunker.chunkText("a short note")).hasSize(1);
    }
}
