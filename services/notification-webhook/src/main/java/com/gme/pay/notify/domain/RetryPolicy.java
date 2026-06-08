package com.gme.pay.notify.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * Exponential-backoff retry policy for webhook dispatch (WBS 8.6-T08).
 *
 * <p>Schedule (attempt number is 1-based, i.e. the attempt that just failed):
 * <pre>
 *   Attempt  Delay before next retry
 *   1        0 s    (immediate retry)
 *   2        30 s
 *   3        120 s
 *   4        600 s  (10 min)
 *   5        1800 s (30 min)
 *   6-10     3600 s (60 min) each
 * </pre>
 *
 * <p>After {@link #MAX_ATTEMPTS} failures the event is promoted to the DLQ.
 */
public class RetryPolicy {

    /** Maximum delivery attempts before DLQ promotion. */
    public static final int MAX_ATTEMPTS = 10;

    /**
     * Delay seconds indexed by attempt number (1-based).
     * Index 0 is unused; indices 1-10 correspond to attempts 1-10.
     */
    private static final long[] DELAY_SECONDS = {
        0,    // unused index 0
        0,    // attempt 1 -> immediate
        30,   // attempt 2
        120,  // attempt 3
        600,  // attempt 4
        1800, // attempt 5
        3600, // attempt 6
        3600, // attempt 7
        3600, // attempt 8
        3600, // attempt 9
        3600  // attempt 10
    };

    /**
     * Computes the {@link Duration} to wait before the next delivery attempt.
     *
     * @param attemptNumber the 1-based number of the attempt that just failed (must be 1-10)
     * @return delay duration (never negative)
     * @throws IllegalArgumentException if {@code attemptNumber} is out of [1, MAX_ATTEMPTS]
     */
    public Duration delayAfterAttempt(int attemptNumber) {
        if (attemptNumber < 1 || attemptNumber > MAX_ATTEMPTS) {
            throw new IllegalArgumentException(
                    "attemptNumber must be between 1 and " + MAX_ATTEMPTS + ", got: " + attemptNumber);
        }
        return Duration.ofSeconds(DELAY_SECONDS[attemptNumber]);
    }

    /**
     * Computes the absolute {@link Instant} at which the next delivery attempt should occur.
     *
     * @param attemptNumber  the 1-based number of the attempt that just failed
     * @param dispatchedAt   time at which the failed dispatch occurred
     * @return absolute timestamp for the next attempt
     */
    public Instant nextAttemptAt(int attemptNumber, Instant dispatchedAt) {
        return dispatchedAt.plus(delayAfterAttempt(attemptNumber));
    }

    /**
     * Returns {@code true} if {@code attemptNumber} has reached or exceeded the DLQ threshold.
     */
    public boolean isDlqThresholdReached(int attemptNumber) {
        return attemptNumber >= MAX_ATTEMPTS;
    }
}
