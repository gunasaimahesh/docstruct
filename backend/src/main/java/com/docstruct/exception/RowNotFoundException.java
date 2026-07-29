package com.docstruct.exception;

import org.springframework.http.HttpStatus;

public class RowNotFoundException extends DocStructException {

    public RowNotFoundException(long rowId) {
        super("Row not found: " + rowId, "ROW_NOT_FOUND", HttpStatus.NOT_FOUND, null);
    }
}
