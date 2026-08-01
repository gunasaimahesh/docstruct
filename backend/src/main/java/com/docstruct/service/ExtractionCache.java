package com.docstruct.service;

import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.docstruct.config.ExtractionCacheProperties;
import com.docstruct.domain.extraction.ExtractionResult;
import com.docstruct.domain.extraction.SchemaMatchResult;
import com.docstruct.util.ContentHash;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * Memoizes extraction results so re-uploading the same bytes doesn't pay for the
 * same LLM call twice. Keys are derived from everything the prompt is built from:
 * the file's SHA-256 for a fresh inference, and the SHA-256 of the file plus the
 * serialized schema when extracting against an existing one — because the same
 * document matched against a different schema is a different question.
 *
 * Misses are loaded outside any cache lock, so two simultaneous uploads of the
 * same file can both call the LLM. That wastes one call in a rare case; the
 * alternative holds a lock across multi-second network I/O.
 */
@Component
public class ExtractionCache {

    private static final Logger log = LoggerFactory.getLogger(ExtractionCache.class);

    private final ExtractionCacheProperties properties;
    private final Cache<String, ExtractionResult> inferences;
    private final Cache<String, SchemaMatchResult> matches;

    public ExtractionCache(ExtractionCacheProperties properties) {
        this.properties = properties;
        this.inferences = build();
        this.matches = build();
    }

    /** Schema inference: the document bytes are the whole input. */
    public ExtractionResult inferred(String contentHash, Supplier<ExtractionResult> extract) {
        return lookup(inferences, contentHash, "schema inference", contentHash, extract);
    }

    /** Schema matching: the existing schema is part of the prompt, so it's part of the key. */
    public SchemaMatchResult matched(String contentHash, String schemaJson,
                                     Supplier<SchemaMatchResult> extract) {
        String key = ContentHash.sha256(contentHash + '\u0000' + schemaJson);
        return lookup(matches, key, "schema match", contentHash, extract);
    }

    private <T> T lookup(Cache<String, T> cache, String key, String what,
                         String contentHash, Supplier<T> extract) {
        if (!properties.enabled()) {
            return extract.get();
        }

        T cached = cache.getIfPresent(key);
        if (cached != null) {
            log.info("Extraction cache hit ({}): reusing result for content {} — no LLM call",
                    what, shortHash(contentHash));
            return cached;
        }

        // Deliberately outside the cache: a failed extraction must not be memoized.
        T fresh = extract.get();
        cache.put(key, fresh);
        return fresh;
    }

    private <T> Cache<String, T> build() {
        return Caffeine.newBuilder()
                .maximumSize(properties.maxEntries())
                .expireAfterWrite(properties.ttl())
                .build();
    }

    private static String shortHash(String contentHash) {
        return contentHash.substring(0, Math.min(12, contentHash.length()));
    }
}
