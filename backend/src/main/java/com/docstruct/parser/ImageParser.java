package com.docstruct.parser;

import java.util.Base64;

import org.springframework.stereotype.Component;

import com.docstruct.domain.DocumentFormat;

/**
 * Images are not OCR'd locally. The base64 payload is passed straight to the
 * LLM, whose vision capability reads layout and content simultaneously —
 * significantly better than local OCR for receipts and invoices.
 */
@Component
public class ImageParser implements DocumentParser {

    @Override
    public DocumentFormat format() {
        return DocumentFormat.IMAGE;
    }

    @Override
    public ParseResult parse(byte[] content, String mimeType) {
        return ParseResult.ofImage(mimeType, Base64.getEncoder().encodeToString(content));
    }
}
