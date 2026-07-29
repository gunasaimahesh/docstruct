package com.docstruct.exception;

import org.springframework.http.HttpStatus;

public class CollectionNotFoundException extends DocStructException {

    public CollectionNotFoundException(String id) {
        super("Collection not found: " + id, "COLLECTION_NOT_FOUND", HttpStatus.NOT_FOUND, null);
    }
}
