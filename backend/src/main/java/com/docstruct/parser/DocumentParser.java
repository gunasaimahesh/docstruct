package com.docstruct.parser;

import com.docstruct.domain.DocumentFormat;

/** Strategy interface — one implementation per supported document format. */
public interface DocumentParser {

    DocumentFormat format();

    ParseResult parse(byte[] content, String mimeType);
}
