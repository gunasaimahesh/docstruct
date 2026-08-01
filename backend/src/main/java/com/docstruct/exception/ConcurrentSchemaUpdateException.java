package com.docstruct.exception;

import org.springframework.http.HttpStatus;

/**
 * Raised when a document could not be written because concurrent uploads kept
 * winning the race for the collection's schema. The upload was not applied —
 * retrying is safe.
 */
public class ConcurrentSchemaUpdateException extends DocStructException {

    public ConcurrentSchemaUpdateException(String collectionId, int attempts) {
        super("This collection is being updated by another upload. Please try again.",
                "SCHEMA_UPDATE_CONFLICT",
                HttpStatus.CONFLICT,
                "Gave up merging the schema for collection %s after %d attempts. No data was written."
                        .formatted(collectionId, attempts));
    }
}
