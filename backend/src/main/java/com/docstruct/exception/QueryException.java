package com.docstruct.exception;

import org.springframework.http.HttpStatus;

public class QueryException extends DocStructException {

    public QueryException(String message) {
        this(message, null);
    }

    public QueryException(String message, String details) {
        super(message, "QUERY_ERROR", HttpStatus.BAD_REQUEST, details);
    }
}
