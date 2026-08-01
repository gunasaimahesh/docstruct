package com.docstruct.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "docstruct.extraction-cache")
public record ExtractionCacheProperties(
        boolean enabled,
        /** Upper bound on cached extractions; the least recently used are evicted first. */
        long maxEntries,
        /** How long an extraction stays reusable — a bound on staleness after a prompt or model change. */
        Duration ttl
) {
}
