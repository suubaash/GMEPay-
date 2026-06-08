package com.gme.pay.notify.signing;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Guards against replay attacks by validating that a webhook timestamp is within the
 * allowed tolerance window (API-05 §6.3: +/- 5 minutes).
 *
 * <p>Clock is injected so tests can use a fixed clock for determinism.
 */
@Component
public class WebhookReplayGuard {

    /** Maximum age (or future skew) before a timestamp is considered expired. */
    static final Duration TOLERANCE = Duration.ofMinutes(5);

    private final Clock clock;

    public WebhookReplayGuard(Clock clock) {
        this.clock = clock;
    }

    /**
     * Returns {@code true} if the ISO-8601 UTC timestamp in {@code timestampHeader} is within
     * the five-minute tolerance window relative to the current time on {@code clock}.
     *
     * @param timestampHeader value of the X-GME-Webhook-Timestamp header
     * @return {@code true} if timestamp is valid (within tolerance), {@code false} otherwise
     */
    public boolean isTimestampValid(String timestampHeader) {
        if (timestampHeader == null || timestampHeader.isBlank()) {
            return false;
        }
        try {
            Instant parsed = Instant.parse(timestampHeader);
            Instant now = Instant.now(clock);
            Duration age = Duration.between(parsed, now).abs();
            return age.compareTo(TOLERANCE) < 0;
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}
