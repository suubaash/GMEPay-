package com.gme.pay.auth.domain;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Validates X-Timestamp for replay-protection (SEC-09 §3.3, API-05 §3.6).
 *
 * Accepted formats:
 *  - ISO-8601 UTC millisecond precision: "2026-06-04T09:31:00.000Z"
 *  - Unix epoch integer string:          "1749034260"
 *
 * Rejects if |server_now - X-Timestamp| > windowSeconds (default 300 s).
 */
public final class TimestampValidator {

    private TimestampValidator() {}

    /**
     * Returns true iff the absolute difference between the parsed timestamp and
     * {@code serverNow} is within {@code windowSeconds} (inclusive at boundary).
     *
     * @param xTimestamp  header value — ISO-8601 UTC or Unix epoch string
     * @param serverNow   current server time (injected for testability)
     * @param windowSeconds maximum allowed drift in seconds
     * @throws IllegalArgumentException if {@code xTimestamp} cannot be parsed
     */
    public static boolean isWithinWindow(String xTimestamp, Instant serverNow, long windowSeconds) {
        Instant ts = parse(xTimestamp);
        long diffSeconds = Math.abs(serverNow.getEpochSecond() - ts.getEpochSecond());
        return diffSeconds <= windowSeconds;
    }

    /**
     * Convenience overload using the default 300-second window.
     */
    public static boolean isWithinWindow(String xTimestamp, Instant serverNow) {
        return isWithinWindow(xTimestamp, serverNow, 300L);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    static Instant parse(String xTimestamp) {
        if (xTimestamp == null || xTimestamp.isBlank()) {
            throw new IllegalArgumentException("X-Timestamp must not be blank");
        }
        if (xTimestamp.contains("T")) {
            // ISO-8601 format
            try {
                return Instant.parse(xTimestamp);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("Unparseable ISO-8601 X-Timestamp: " + xTimestamp, e);
            }
        }
        // Unix epoch integer format
        try {
            long epoch = Long.parseLong(xTimestamp.trim());
            return Instant.ofEpochSecond(epoch);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Unparseable X-Timestamp (not ISO-8601 and not numeric): " + xTimestamp, e);
        }
    }
}
