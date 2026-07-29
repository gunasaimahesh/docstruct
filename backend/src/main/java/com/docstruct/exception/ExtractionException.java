package com.docstruct.exception;

import org.springframework.http.HttpStatus;

public class ExtractionException extends DocStructException {

    public ExtractionException(String message) {
        this(message, null);
    }

    public ExtractionException(String message, String details) {
        super(message, "EXTRACTION_ERROR", HttpStatus.UNPROCESSABLE_ENTITY, details);
    }
}
