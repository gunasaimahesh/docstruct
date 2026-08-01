package com.docstruct.exception;

import org.springframework.http.HttpStatus;

/**
 * Raised when a client has spent its budget of AI-backed requests for the current
 * window. Nothing was sent to the model and nothing was written, so retrying after
 * {@link #getRetryAfterSeconds()} is safe.
 */
public class RateLimitExceededException extends DocStructException {

    private final long retryAfterSeconds;

    public RateLimitExceededException(long retryAfterSeconds, int capacity, long windowSeconds) {
        super("Too many AI requests. Please try again in %d second(s).".formatted(retryAfterSeconds),
                "RATE_LIMIT_EXCEEDED",
                HttpStatus.TOO_MANY_REQUESTS,
                ("The limit is %d AI-backed request(s) per %d second(s) per client. "
                        + "Nothing was sent to the model.").formatted(capacity, windowSeconds));
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** Seconds the client should wait, surfaced as the {@code Retry-After} response header. */
    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
