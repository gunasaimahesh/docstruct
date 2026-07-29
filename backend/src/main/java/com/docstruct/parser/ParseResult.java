package com.docstruct.parser;

import java.util.Map;

import com.docstruct.domain.DocumentFormat;

/**
 * The output of parsing a document.
 * For text-based formats, {@code text} holds the extracted content.
 * For images, no local OCR happens — the base64 payload is carried through
 * so the LLM's vision capability can read it directly.
 */
public record ParseResult(
        String text,
        DocumentFormat format,
        String imageMimeType,
        String imageBase64,
        Map<String, Object> metadata
) {
    public static ParseResult ofText(String text, DocumentFormat format, Map<String, Object> metadata) {
        return new ParseResult(text, format, null, null, metadata);
    }

    public static ParseResult ofImage(String mimeType, String base64) {
        return new ParseResult("", DocumentFormat.IMAGE, mimeType, base64, Map.of("hasImage", true));
    }

    public boolean isImage() {
        return imageBase64 != null;
    }
}
