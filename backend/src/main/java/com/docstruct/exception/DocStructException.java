package com.docstruct.exception;

import org.springframework.http.HttpStatus;

/**
 * Base class for all business exceptions.
 * Carries a machine-readable code and the HTTP status the API should respond with.
 */
public abstract class DocStructException extends RuntimeException {

    private final String code;
    private final HttpStatus status;
    private final String details;

    protected DocStructException(String message, String code, HttpStatus status, String details) {
        super(message);
        this.code = code;
        this.status = status;
        this.details = details;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getDetails() {
        return details;
    }
}
