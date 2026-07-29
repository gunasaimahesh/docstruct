package com.docstruct.exception;

import org.springframework.http.HttpStatus;

public class ParseException extends DocStructException {

    public ParseException(String message) {
        this(message, null);
    }

    public ParseException(String message, String details) {
        super(message, "PARSE_ERROR", HttpStatus.UNPROCESSABLE_ENTITY, details);
    }
}
