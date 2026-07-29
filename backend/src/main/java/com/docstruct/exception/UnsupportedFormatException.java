package com.docstruct.exception;

import org.springframework.http.HttpStatus;

public class UnsupportedFormatException extends DocStructException {

    public UnsupportedFormatException(String format) {
        super("Unsupported document format: " + format,
                "UNSUPPORTED_FORMAT",
                HttpStatus.BAD_REQUEST,
                "Supported formats: PDF, PNG, JPG, CSV, TXT");
    }
}
