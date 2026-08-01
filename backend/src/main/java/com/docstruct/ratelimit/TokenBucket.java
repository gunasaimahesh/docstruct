package com.docstruct.ratelimit;

import java.time.Duration;

/**
 * One client's budget: {@code capacity} tokens that refill continuously — one every
 * {@code window / capacity} — and never exceed the cap. A bucket starts full, so a
 * first-time client isn't throttled, and a burst of {@code capacity} requests is
 * allowed before the drip rate takes over.
 *
 * The clock is passed in rather than read here so the refill arithmetic can be
 * tested at exact instants instead of by sleeping.
 */
final class TokenBucket {

    /** Whether the request may proceed and, if not, how long until it could. */
    record Verdict(boolean allowed, long retryAfterNanos) {

        static final Verdict ALLOWED = new Verdict(true, 0L);
    }

    private final long capacity;
    private final long refillIntervalNanos;

    private long tokens;
    private long lastRefillNanos;

    TokenBucket(long capacity, Duration window, long nowNanos) {
        this.capacity = capacity;
        // At least one nanosecond per token: a window shorter than its own capacity
        // would otherwise divide to zero and make the refill maths undefined.
        this.refillIntervalNanos = Math.max(1L, window.toNanos() / capacity);
        this.tokens = capacity;
        this.lastRefillNanos = nowNanos;
    }

    synchronized Verdict tryConsume(long nowNanos) {
        refill(nowNanos);

        if (tokens > 0) {
            tokens--;
            return Verdict.ALLOWED;
        }

        // Empty, so the next token is one full interval after the last one credited.
        long retryAfterNanos = Math.max(1L, lastRefillNanos + refillIntervalNanos - nowNanos);
        return new Verdict(false, retryAfterNanos);
    }

    private void refill(long nowNanos) {
        long elapsed = nowNanos - lastRefillNanos;
        if (elapsed < refillIntervalNanos) {
            // Not a whole token's worth yet. lastRefillNanos is left alone so the
            // partial progress carries into the next call instead of being dropped.
            return;
        }

        long earned = elapsed / refillIntervalNanos;
        tokens = Math.min(capacity, tokens + earned);

        if (tokens == capacity) {
            // Time spent at the cap earns nothing. Without this, a long idle period
            // would bank a remainder and hand out an instant refill after the next spend.
            lastRefillNanos = nowNanos;
        } else {
            lastRefillNanos += earned * refillIntervalNanos;
        }
    }
}
