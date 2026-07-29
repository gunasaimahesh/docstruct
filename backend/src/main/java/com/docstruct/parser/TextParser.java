package com.docstruct.parser;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.docstruct.domain.DocumentFormat;
import com.docstruct.exception.ParseException;

@Component
public class TextParser implements DocumentParser {

    @Override
    public DocumentFormat format() {
        return DocumentFormat.TEXT;
    }

    @Override
    public ParseResult parse(byte[] content, String mimeType) {
        String text = new String(content, StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) {
            throw new ParseException("Text file is empty");
        }
        return ParseResult.ofText(text, DocumentFormat.TEXT, Map.of(
                "lineCount", text.split("\n").length,
                "charCount", text.length()));
    }
}
