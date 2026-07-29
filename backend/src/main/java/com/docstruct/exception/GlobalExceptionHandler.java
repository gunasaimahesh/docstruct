package com.docstruct.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import com.docstruct.config.UploadProperties;
import com.docstruct.dto.ErrorResponse;

/**
 * Maps exceptions to the consistent error contract:
 * {@code { success: false, error, code, details? }}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final UploadProperties uploadProperties;

    public GlobalExceptionHandler(UploadProperties uploadProperties) {
        this.uploadProperties = uploadProperties;
    }

    @ExceptionHandler(DocStructException.class)
    public ResponseEntity<ErrorResponse> handleDocStruct(DocStructException e) {
        log.warn("{}: {}", e.getCode(), e.getMessage());
        return ResponseEntity.status(e.getStatus())
                .body(ErrorResponse.of(e.getMessage(), e.getCode(), e.getDetails()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + " " + err.getDefaultMessage())
                .findFirst()
                .orElse("Validation failed");
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(message, "VALIDATION_ERROR", null));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ErrorResponse> handleMissingPart(MissingServletRequestPartException e) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("No file provided", "VALIDATION_ERROR", e.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUpload(MaxUploadSizeExceededException e) {
        String message = "File too large. Maximum size is %.0fMB.".formatted(
                uploadProperties.maxFileSizeBytes() / 1024.0 / 1024.0);
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ErrorResponse.of(message, "FILE_TOO_LARGE", null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        // The full exception goes to the log only: internal messages can leak
        // SQL, file paths or driver details and must never reach the client.
        log.error("Unexpected error", e);
        return ResponseEntity.internalServerError()
                .body(ErrorResponse.of("An unexpected error occurred.", "INTERNAL_ERROR", null));
    }
}
