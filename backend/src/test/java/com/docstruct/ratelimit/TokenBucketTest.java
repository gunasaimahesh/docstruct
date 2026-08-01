package com.docstruct.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/**
 * The refill arithmetic, driven by an explicit clock rather than by sleeping.
 * Capacity 3 over a 60-second window means one token every 20 seconds.
 */
class TokenBucketTest {

    private static final int CAPACITY = 3;
    private static final Duration WINDOW = Duration.ofSeconds(60);
    private static final long INTERVAL = Duration.ofSeconds(20).toNanos();

    private static long seconds(long value) {
        return Duration.ofSeconds(value).toNanos();
    }

    private TokenBucket bucket() {
        return new TokenBucket(CAPACITY, WINDOW, 0L);
    }

    /** Spends the whole starting allowance at {@code atNanos}, asserting each call is allowed. */
    private static void drain(TokenBucket bucket, long atNanos) {
        for (int i = 0; i < CAPACITY; i++) {
            assertThat(bucket.tryConsume(atNanos).allowed())
                    .as("token %d of the burst", i + 1)
                    .isTrue();
        }
    }

    @Test
    void startsFullSoAFirstTimeClientIsNotThrottled() {
        drain(bucket(), 0L);
    }

    @Test
    void refusesOnceTheBurstIsSpent() {
        TokenBucket bucket = bucket();
        drain(bucket, 0L);

        TokenBucket.Verdict verdict = bucket.tryConsume(0L);

        assertThat(verdict.allowed()).isFalse();
        assertThat(verdict.retryAfterNanos()).isEqualTo(INTERVAL);
    }

    @Test
    void retryAfterShrinksAsTheIntervalElapses() {
        TokenBucket bucket = bucket();
        drain(bucket, 0L);

        assertThat(bucket.tryConsume(seconds(5)).retryAfterNanos()).isEqualTo(seconds(15));
        assertThat(bucket.tryConsume(seconds(19)).retryAfterNanos()).isEqualTo(seconds(1));
    }

    @Test
    void oneTokenAccruesEachInterval() {
        TokenBucket bucket = bucket();
        drain(bucket, 0L);

        assertThat(bucket.tryConsume(seconds(20)).allowed()).isTrue();
        assertThat(bucket.tryConsume(seconds(20)).allowed()).isFalse();
        assertThat(bucket.tryConsume(seconds(40)).allowed()).isTrue();
    }

    @Test
    void progressTowardsTheNextTokenSurvivesRejectedCalls() {
        TokenBucket bucket = bucket();
        drain(bucket, 0L);

        // Rejected at 19s, so the 19 seconds already served must still count at 21s.
        assertThat(bucket.tryConsume(seconds(19)).allowed()).isFalse();
        assertThat(bucket.tryConsume(seconds(21)).allowed()).isTrue();
    }

    @Test
    void refillNeverExceedsCapacity() {
        TokenBucket bucket = bucket();
        drain(bucket, 0L);

        // Five windows' worth of idle time is still only one window's worth of allowance.
        drain(bucket, seconds(300));
        assertThat(bucket.tryConsume(seconds(300)).allowed()).isFalse();
    }

    @Test
    void timeSpentAtTheCapDoesNotBankAnEarlyRefill() {
        TokenBucket bucket = bucket();
        drain(bucket, 0L);

        // Idle to 305s: the bucket refilled at 300s and then sat full for 5 seconds.
        // Those 5 seconds must not count towards the next token, or the burst that
        // follows is rewarded for having waited longer than it needed to.
        drain(bucket, seconds(305));

        assertThat(bucket.tryConsume(seconds(305)).retryAfterNanos()).isEqualTo(INTERVAL);
    }

    @Test
    void aWindowShorterThanItsCapacityStillRefills() {
        // 3 tokens per nanosecond would divide to a zero-length interval.
        TokenBucket bucket = new TokenBucket(CAPACITY, Duration.ofNanos(1), 0L);
        drain(bucket, 0L);

        assertThat(bucket.tryConsume(0L).allowed()).isFalse();
        assertThat(bucket.tryConsume(1L).allowed()).isTrue();
    }
}
