package com.gme.pay.gateway.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link InMemoryRateLimitStore}: fixed-window counting, breach at limit+1,
 * remaining/reset headroom, per-key isolation, and window roll-over.
 */
class InMemoryRateLimitStoreTest {

    private static final Duration WINDOW = Duration.ofSeconds(1);

    @Test
    void allowsUpToLimit_thenRejects() {
        InMemoryRateLimitStore store = new InMemoryRateLimitStore(
                Clock.fixed(Instant.parse("2026-06-16T00:00:00Z"), ZoneOffset.UTC));

        for (int i = 1; i <= 3; i++) {
            RateLimitStore.Decision d = store.recordHit("p1:rates", 3, WINDOW).block();
            assertTrue(d.allowed(), "hit " + i + " of 3 must be allowed");
            assertEquals(3 - i, d.remaining(), "remaining decrements toward zero");
        }
        RateLimitStore.Decision over = store.recordHit("p1:rates", 3, WINDOW).block();
        assertFalse(over.allowed(), "the 4th hit in a 3/window limit is rejected");
        assertEquals(0, over.remaining());
        assertTrue(over.resetAfterSeconds() >= 1, "reset advises at least the next whole second");
    }

    @Test
    void keysAreIndependent() {
        InMemoryRateLimitStore store = new InMemoryRateLimitStore(
                Clock.fixed(Instant.parse("2026-06-16T00:00:00Z"), ZoneOffset.UTC));

        store.recordHit("p1:global", 1, WINDOW).block();
        assertFalse(store.recordHit("p1:global", 1, WINDOW).block().allowed(),
                "p1 has exhausted its limit");
        assertTrue(store.recordHit("p2:global", 1, WINDOW).block().allowed(),
                "p2 has an independent counter");
    }

    @Test
    void counterResetsInNextWindow() {
        AdjustableClock clock = new AdjustableClock(Instant.parse("2026-06-16T00:00:00Z"));
        InMemoryRateLimitStore store = new InMemoryRateLimitStore(clock);

        assertTrue(store.recordHit("p1:rates", 1, WINDOW).block().allowed());
        assertFalse(store.recordHit("p1:rates", 1, WINDOW).block().allowed(),
                "second hit in same 1s window is rejected");

        clock.advance(Duration.ofMillis(1001)); // roll into the next fixed window
        assertTrue(store.recordHit("p1:rates", 1, WINDOW).block().allowed(),
                "the counter resets once the window elapses");
    }

    /** A mutable Clock so the window-rollover test can advance time deterministically. */
    private static final class AdjustableClock extends Clock {
        private Instant now;

        AdjustableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
