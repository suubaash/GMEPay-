package com.gme.pay.gateway.replay;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link InMemoryNonceStore}: fresh accept, replay reject, per-partner scope, TTL expiry. */
class InMemoryNonceStoreTest {

    private static final Duration TTL = Duration.ofMinutes(5);

    @Test
    void freshAcceptsReplayRejects_andScopedPerPartner() {
        InMemoryNonceStore store = new InMemoryNonceStore(
                Clock.fixed(Instant.parse("2026-06-16T00:00:00Z"), ZoneOffset.UTC));

        assertTrue(store.checkAndSet("p1", "n1", TTL).block(), "first use is fresh");
        assertFalse(store.checkAndSet("p1", "n1", TTL).block(), "same (partner,nonce) is a replay");
        assertTrue(store.checkAndSet("p1", "n2", TTL).block(), "different nonce is fresh");
        assertTrue(store.checkAndSet("p2", "n1", TTL).block(), "same nonce, different partner is fresh");
    }

    @Test
    void nonceIsReusableAfterTtlExpires() {
        AdjustableClock clock = new AdjustableClock(Instant.parse("2026-06-16T00:00:00Z"));
        InMemoryNonceStore store = new InMemoryNonceStore(clock);

        assertTrue(store.checkAndSet("p1", "n1", TTL).block());
        assertFalse(store.checkAndSet("p1", "n1", TTL).block(), "replay within TTL is rejected");

        clock.advance(Duration.ofMinutes(6)); // past the 5-minute TTL
        assertTrue(store.checkAndSet("p1", "n1", TTL).block(),
                "nonce is reusable once its TTL has elapsed");
    }

    /** A mutable Clock so the TTL-expiry test can advance time deterministically. */
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
