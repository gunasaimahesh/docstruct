package com.docstruct.exception;

import org.springframework.http.HttpStatus;

public class ValidationException extends DocStructException {

    public ValidationException(String message) {
        this(message, null);
    }

    public ValidationException(String message, String details) {
        super(message, "VALIDATION_ERROR", HttpStatus.BAD_REQUEST, details);
    }
}
