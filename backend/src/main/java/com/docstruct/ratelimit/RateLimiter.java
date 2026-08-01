package com.docstruct.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.docstruct.config.RateLimitProperties;
import com.docstruct.exception.RateLimitExceededException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * A per-client budget on the endpoints that can reach the LLM, so a stuck browser
 * tab or a script in a loop can't spend the whole API balance in a few seconds.
 *
 * State is a bounded map of token buckets in this process only — deliberately not
 * distributed. Buckets are dropped once a client has been idle for a full window,
 * which is safe because an idle bucket has refilled to capacity by then, making a
 * dropped bucket and a fresh one indistinguishable.
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final RateLimitProperties properties;
    private final Cache<String, TokenBucket> buckets;

    public RateLimiter(RateLimitProperties properties) {
        this.properties = properties;
        this.buckets = Caffeine.newBuilder()
                .maximumSize(properties.maxTrackedClients())
                .expireAfterAccess(properties.window())
                .build();
    }

    /**
     * Spends one of {@code clientId}'s tokens.
     *
     * @throws RateLimitExceededException if the client's budget for this window is gone
     */
    public void consumeOrReject(String clientId) {
        if (!properties.enabled()) {
            return;
        }

        long nowNanos = System.nanoTime();
        TokenBucket bucket = buckets.get(clientId,
                key -> new TokenBucket(properties.capacity(), properties.window(), nowNanos));

        TokenBucket.Verdict verdict = bucket.tryConsume(nowNanos);
        if (verdict.allowed()) {
            return;
        }

        long retryAfterSeconds = ceilToSeconds(verdict.retryAfterNanos());
        log.warn("Rate limit exceeded for client {}: {} request(s) per {}s exhausted, retry after {}s",
                clientId, properties.capacity(), properties.window().toSeconds(), retryAfterSeconds);

        throw new RateLimitExceededException(
                retryAfterSeconds, properties.capacity(), properties.window().toSeconds());
    }

    /** Rounded up, and never zero: a {@code Retry-After: 0} invites an immediate retry that also fails. */
    private static long ceilToSeconds(long nanos) {
        return Math.max(1L, (nanos + NANOS_PER_SECOND - 1) / NANOS_PER_SECOND);
    }
}
