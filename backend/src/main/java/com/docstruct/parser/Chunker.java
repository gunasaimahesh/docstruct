package com.docstruct.parser;

import java.util.ArrayList;
import java.util.List;

import com.docstruct.domain.extraction.DocumentChunk;

/**
 * Splits parsed document text into numbered, page-tagged chunks.
 *
 * This is NOT a retrieval index — every chunk is still sent to the LLM. The
 * point is addressability: once the prompt presents the document as numbered
 * chunks, the model can cite the exact chunk a value came from and the backend
 * can verify that citation against this same text (see ConfidenceScorer).
 *
 * Chunking is purely deterministic (same input, same chunk boundaries), which
 * is what makes a stored citation meaningful after the fact.
 */
public final class Chunker {

    /** Soft target size. Chunks break on paragraph/line boundaries, so actual sizes vary. */
    static final int TARGET_CHUNK_CHARS = 1200;

    /** A single paragraph longer than this is split further, on line boundaries. */
    static final int MAX_CHUNK_CHARS = TARGET_CHUNK_CHARS * 2;

    private Chunker() {
    }

    /** Chunks a single-page document (text, markdown, log files). */
    public static List<DocumentChunk> chunkText(String text) {
        return chunkPages(List.of(text));
    }

    /**
     * Chunks a paginated document, preserving real page numbers (1-based).
     * A page never shares a chunk with another page, so a citation always
     * resolves to exactly one page.
     */
    public static List<DocumentChunk> chunkPages(List<String> pageTexts) {
        List<DocumentChunk> chunks = new ArrayList<>();
        int index = 1;

        for (int page = 0; page < pageTexts.size(); page++) {
            String pageText = pageTexts.get(page) == null ? "" : pageTexts.get(page).strip();
            if (pageText.isEmpty()) {
                continue;
            }
            for (String body : splitIntoBlocks(pageText)) {
                chunks.add(new DocumentChunk(index++, page + 1, body));
            }
        }
        return chunks;
    }

    /**
     * Chunks delimited data, repeating the header row in every chunk.
     *
     * Without the repeated header, a chunk of bare data rows is uninterpretable
     * on its own — the model would have to guess which column is which, which is
     * exactly the kind of inference we are trying to eliminate.
     */
    public static List<DocumentChunk> chunkDelimited(String raw) {
        List<String> lines = raw.lines().filter(line -> !line.isBlank()).toList();
        if (lines.size() <= 1) {
            return chunkText(raw);
        }

        String header = lines.get(0);
        List<DocumentChunk> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder(header);
        int index = 1;

        for (String line : lines.subList(1, lines.size())) {
            if (current.length() + line.length() + 1 > TARGET_CHUNK_CHARS && current.length() > header.length()) {
                chunks.add(new DocumentChunk(index++, 1, current.toString()));
                current = new StringBuilder(header);
            }
            current.append('\n').append(line);
        }
        if (current.length() > header.length()) {
            chunks.add(new DocumentChunk(index, 1, current.toString()));
        }
        return chunks;
    }

    /** Groups paragraphs up to the target size, splitting oversized paragraphs by line. */
    private static List<String> splitIntoBlocks(String pageText) {
        List<String> blocks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String paragraph : pageText.split("\\n\\s*\\n")) {
            String trimmed = paragraph.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            for (String piece : splitOversized(trimmed)) {
                if (!current.isEmpty() && current.length() + piece.length() > TARGET_CHUNK_CHARS) {
                    blocks.add(current.toString());
                    current = new StringBuilder();
                }
                if (!current.isEmpty()) {
                    current.append("\n\n");
                }
                current.append(piece);
            }
        }

        if (!current.isEmpty()) {
            blocks.add(current.toString());
        }
        return blocks.isEmpty() ? List.of(pageText) : blocks;
    }

    /** Splits a single over-long paragraph on line boundaries (tables, dense invoices). */
    private static List<String> splitOversized(String paragraph) {
        if (paragraph.length() <= MAX_CHUNK_CHARS) {
            return List.of(paragraph);
        }

        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : paragraph.split("\\n")) {
            if (!current.isEmpty() && current.length() + line.length() > TARGET_CHUNK_CHARS) {
                pieces.add(current.toString());
                current = new StringBuilder();
            }
            if (!current.isEmpty()) {
                current.append('\n');
            }
            current.append(line);
        }
        if (!current.isEmpty()) {
            pieces.add(current.toString());
        }
        return pieces;
    }
}
