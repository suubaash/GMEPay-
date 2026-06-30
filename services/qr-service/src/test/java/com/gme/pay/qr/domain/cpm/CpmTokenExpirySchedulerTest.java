package com.gme.pay.qr.domain.cpm;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies the expiry sweep releases OVERSEAS prefunding holds (Phase 2, IR-qr-3). */
class CpmTokenExpirySchedulerTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-06-30T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void sweepReleasesReservationKeyedOnCpmTokenId() {
        StubStore store = new StubStore(List.of(
                new CpmSessionStorePort.ExpiredSession("TOK-A", 7L, "RSV-A"),
                new CpmSessionStorePort.ExpiredSession("TOK-B", null, null))); // local, no hold
        RecordingReservationPort prefunding = new RecordingReservationPort();

        new CpmTokenExpiryScheduler(store, prefunding, clock).sweep();

        assertEquals(1, prefunding.releases.size(), "only the OVERSEAS hold is released");
        var rel = prefunding.releases.get(0);
        assertEquals(7L, rel.partnerId());
        assertEquals("RSV-A", rel.reservationId());
        assertEquals("TOK-A", rel.idempotencyKey(), "release key must be the CPM token id");
    }

    @Test
    void sweepReleaseIsIdempotentAcrossRuns() {
        var hold = new CpmSessionStorePort.ExpiredSession("TOK-A", 7L, "RSV-A");
        // first sweep returns the hold; subsequent sweeps return nothing (already EXPIRED)
        StubStore store = new StubStore(List.of(hold));
        RecordingReservationPort prefunding = new RecordingReservationPort();
        CpmTokenExpiryScheduler scheduler = new CpmTokenExpiryScheduler(store, prefunding, clock);

        scheduler.sweep();
        scheduler.sweep();

        assertEquals(1, prefunding.releases.size(), "expired rows are not re-swept → release runs once");
    }

    @Test
    void emptySweepDoesNotTouchPrefunding() {
        RecordingReservationPort prefunding = new RecordingReservationPort();
        new CpmTokenExpiryScheduler(new StubStore(List.of()), prefunding, clock).sweep();
        assertTrue(prefunding.releases.isEmpty());
    }

    /** Returns its batch on the first sweep only, mimicking the idempotent JPA sweep. */
    private static final class StubStore implements CpmSessionStorePort {
        private final List<ExpiredSession> firstBatch;
        private final AtomicInteger calls = new AtomicInteger();

        StubStore(List<ExpiredSession> firstBatch) { this.firstBatch = firstBatch; }

        @Override public List<ExpiredSession> expireOverdue(Instant cutoff) {
            return calls.getAndIncrement() == 0 ? firstBatch : List.of();
        }

        @Override public boolean existsByPartnerTxnRef(String ref) { return false; }
        @Override public void save(CpmToken t, String d, String c, String cr, boolean s,
                                   PrefundReservation r) { }
        @Override public Optional<CpmToken> findByPaymentId(String paymentId) { return Optional.empty(); }
    }

    private static final class RecordingReservationPort implements PrefundingReservationPort {
        record Release(long partnerId, String reservationId, String idempotencyKey, String reason) {}
        final List<Release> releases = new ArrayList<>();

        @Override public Reservation reserve(long p, BigDecimal a, String k, String t) {
            return new Reservation("RSV", a);
        }

        @Override public void release(long partnerId, String reservationId, String key, String reason) {
            releases.add(new Release(partnerId, reservationId, key, reason));
        }
    }
}
