package com.docstruct.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "docstruct.rate-limit")
public record RateLimitProperties(
        boolean enabled,
        /** AI-backed requests a single client may make per {@code window}. */
        int capacity,
        /** The period over which a client's full allowance refills. */
        Duration window,
        /** Cap on how many clients are tracked at once, so the limiter can't grow without bound. */
        long maxTrackedClients
) {
}
