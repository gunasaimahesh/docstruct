package com.docstruct.parser;

import java.util.List;
import java.util.Map;

import com.docstruct.domain.DocumentFormat;
import com.docstruct.domain.extraction.DocumentChunk;

/**
 * The output of parsing a document.
 * For text-based formats, {@code text} holds the extracted content and
 * {@code chunks} holds the same content split into numbered, page-tagged slices
 * that the LLM must cite when extracting values.
 * For images, no local OCR happens — the base64 payload is carried through
 * so the LLM's vision capability can read it directly, and {@code chunks} is
 * empty because there is no source text to attribute values to.
 */
public record ParseResult(
        String text,
        DocumentFormat format,
        String imageMimeType,
        String imageBase64,
        List<DocumentChunk> chunks,
        Map<String, Object> metadata
) {
    /** Single-page text: chunk boundaries are derived from the text itself. */
    public static ParseResult ofText(String text, DocumentFormat format, Map<String, Object> metadata) {
        return ofText(text, format, Chunker.chunkText(text), metadata);
    }

    /** Text with parser-supplied chunks (PDF pages, delimited rows with repeated headers). */
    public static ParseResult ofText(String text, DocumentFormat format,
                                     List<DocumentChunk> chunks, Map<String, Object> metadata) {
        return new ParseResult(text, format, null, null, chunks, metadata);
    }

    public static ParseResult ofImage(String mimeType, String base64) {
        return new ParseResult("", DocumentFormat.IMAGE, mimeType, base64, List.of(), Map.of("hasImage", true));
    }

    public boolean isImage() {
        return imageBase64 != null;
    }
}
