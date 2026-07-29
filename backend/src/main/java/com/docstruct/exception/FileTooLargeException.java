package com.docstruct.exception;

import org.springframework.http.HttpStatus;

public class FileTooLargeException extends DocStructException {

    public FileTooLargeException(long sizeBytes, long maxBytes) {
        super("File too large (%.1fMB). Maximum size is %.1fMB.".formatted(
                        sizeBytes / 1024.0 / 1024.0, maxBytes / 1024.0 / 1024.0),
                "FILE_TOO_LARGE",
                HttpStatus.PAYLOAD_TOO_LARGE,
                null);
    }
}
