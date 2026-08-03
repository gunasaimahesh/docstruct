package com.docstruct.exception;

import org.springframework.http.HttpStatus;

public class DocumentNotFoundException extends DocStructException {

    public DocumentNotFoundException(String id) {
        super("Document not found: " + id, "DOCUMENT_NOT_FOUND", HttpStatus.NOT_FOUND, null);
    }
}
