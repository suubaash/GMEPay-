package com.gme.pay.auth.service;

import com.gme.pay.auth.domain.TimestampValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plain JUnit 5 unit tests for {@link TimestampValidator}.
 * No Spring context, no Docker, no Testcontainers — deterministic, fast.
 */
class TimestampValidatorTest {

    private static final long WINDOW = 300L;
    private static final Instant NOW = Instant.parse("2026-06-04T09:31:00.000Z");

    // ── Boundary: exactly at window edge (inclusive) ─────────────────────────

    @Test
    void isWithinWindow_exactlyAtBoundary_returnsTrue() {
        // 300 s before NOW → diff == 300 → within window (inclusive)
        Instant ts = NOW.minusSeconds(300);
        assertTrue(TimestampValidator.isWithinWindow(ts.toString(), NOW, WINDOW));
    }

    @Test
    void isWithinWindow_oneSecondPastBoundary_returnsFalse() {
        // 301 s before NOW → diff == 301 → outside window
        Instant ts = NOW.minusSeconds(301);
        assertFalse(TimestampValidator.isWithinWindow(ts.toString(), NOW, WINDOW));
    }

    // ── Future timestamps beyond window are also rejected ────────────────────

    @Test
    void isWithinWindow_futureTimestampBeyondWindow_returnsFalse() {
        // 301 s after NOW
        Instant ts = NOW.plusSeconds(301);
        assertFalse(TimestampValidator.isWithinWindow(ts.toString(), NOW, WINDOW));
    }

    @Test
    void isWithinWindow_futureTimestampAtBoundary_returnsTrue() {
        // Exactly 300 s in the future — still within symmetric window
        Instant ts = NOW.plusSeconds(300);
        assertTrue(TimestampValidator.isWithinWindow(ts.toString(), NOW, WINDOW));
    }

    // ── Unix epoch integer format ─────────────────────────────────────────────

    @Test
    void isWithinWindow_unixEpochString_parsesCorrectly() {
        // Use NOW's epoch second as the timestamp
        String epochString = String.valueOf(NOW.getEpochSecond());
        assertTrue(TimestampValidator.isWithinWindow(epochString, NOW, WINDOW),
                "Unix epoch string representing NOW must be within window");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1749034260", "1749034260000"})
    void isWithinWindow_unixEpochVariants_doNotThrow(String epochString) {
        // We only require no exception for non-ISO-8601 numeric strings.
        // Whether it falls in or out of window depends on the value;
        // the key assertion is that parsing succeeds without exception.
        assertDoesNotThrow(() ->
                TimestampValidator.isWithinWindow(epochString, Instant.now(), WINDOW));
    }

    // ── Malformed input throws ────────────────────────────────────────────────

    @Test
    void isWithinWindow_blankTimestamp_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> TimestampValidator.isWithinWindow("", NOW, WINDOW));
    }

    @Test
    void isWithinWindow_garbageString_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> TimestampValidator.isWithinWindow("not-a-timestamp", NOW, WINDOW));
    }
}
