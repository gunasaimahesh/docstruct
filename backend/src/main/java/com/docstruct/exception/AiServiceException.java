package com.docstruct.exception;

import org.springframework.http.HttpStatus;

public class AiServiceException extends DocStructException {

    public AiServiceException(String message) {
        this(message, null);
    }

    public AiServiceException(String message, String details) {
        super("AI service error: " + message, "AI_SERVICE_ERROR", HttpStatus.BAD_GATEWAY, details);
    }
}
