package com.gme.pay.notify.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RetryPolicy} — verifies the full backoff schedule and
 * DLQ-threshold logic without any Spring context, DB, or network.
 */
class RetryPolicyTest {

    private RetryPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new RetryPolicy();
    }

    // ------------------------------------------------------------------
    // Backoff schedule
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "attempt {0} -> delay {1}s")
    @CsvSource({
        "1,  0",
        "2,  30",
        "3,  120",
        "4,  600",
        "5,  1800",
        "6,  3600",
        "7,  3600",
        "8,  3600",
        "9,  3600",
        "10, 3600"
    })
    @DisplayName("delayAfterAttempt matches the canonical backoff schedule")
    void backoffSchedule(int attempt, long expectedSeconds) {
        Duration delay = policy.delayAfterAttempt(attempt);
        assertEquals(Duration.ofSeconds(expectedSeconds), delay,
                "Attempt " + attempt + " delay mismatch");
    }

    @Test
    @DisplayName("delayAfterAttempt(1) == Duration.ZERO (immediate retry)")
    void attempt1_isImmediate() {
        assertEquals(Duration.ZERO, policy.delayAfterAttempt(1));
    }

    @Test
    @DisplayName("delayAfterAttempt rejects attempt 0 (below minimum)")
    void attempt0_throws() {
        assertThrows(IllegalArgumentException.class, () -> policy.delayAfterAttempt(0));
    }

    @Test
    @DisplayName("delayAfterAttempt rejects attempt 11 (above maximum)")
    void attempt11_throws() {
        assertThrows(IllegalArgumentException.class, () -> policy.delayAfterAttempt(11));
    }

    // ------------------------------------------------------------------
    // nextAttemptAt
    // ------------------------------------------------------------------

    @Test
    @DisplayName("nextAttemptAt(1, now) == now for immediate retry")
    void nextAttemptAt_attempt1_isNow() {
        Instant now = Instant.parse("2026-06-08T10:00:00Z");
        Instant next = policy.nextAttemptAt(1, now);
        assertEquals(now, next, "Attempt 1 should retry immediately (same instant)");
    }

    @Test
    @DisplayName("nextAttemptAt(2, now) == now + 30s")
    void nextAttemptAt_attempt2() {
        Instant now = Instant.parse("2026-06-08T10:00:00Z");
        Instant next = policy.nextAttemptAt(2, now);
        assertEquals(now.plusSeconds(30), next);
    }

    @Test
    @DisplayName("nextAttemptAt(5, now) == now + 1800s")
    void nextAttemptAt_attempt5() {
        Instant now = Instant.parse("2026-06-08T10:00:00Z");
        Instant next = policy.nextAttemptAt(5, now);
        assertEquals(now.plusSeconds(1800), next);
    }

    // ------------------------------------------------------------------
    // DLQ threshold
    // ------------------------------------------------------------------

    @Test
    @DisplayName("isDlqThresholdReached returns false for attempt 9")
    void dlq_attempt9_notReached() {
        assertFalse(policy.isDlqThresholdReached(9));
    }

    @Test
    @DisplayName("isDlqThresholdReached returns true for attempt 10")
    void dlq_attempt10_reached() {
        assertTrue(policy.isDlqThresholdReached(10));
    }

    @Test
    @DisplayName("MAX_ATTEMPTS is 10")
    void maxAttemptsConstant() {
        assertEquals(10, RetryPolicy.MAX_ATTEMPTS);
    }

    // ------------------------------------------------------------------
    // Backoff schedule is capped (not unbounded exponential)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Backoff cap: attempts 6-10 all return exactly 3600s")
    void backoffCap_attempts6to10() {
        for (int i = 6; i <= 10; i++) {
            assertEquals(Duration.ofSeconds(3600), policy.delayAfterAttempt(i),
                    "Attempt " + i + " must be capped at 3600s");
        }
    }
}
