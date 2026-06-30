package com.gme.pay.qr.domain.cpm;

import com.gme.pay.qr.exception.DuplicatePartnerTxnRefException;
import com.gme.pay.qr.exception.QRErrorCode;
import com.gme.pay.qr.exception.QRParseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Orchestration of the CPM generate flow (WBS 5.3-T07) with an in-memory session store. */
class CpmGenerateServiceTest {

    private InMemorySessionStore store;
    private RecordingReservationPort reservation;
    private CpmGenerateService service;

    @BeforeEach
    void setUp() {
        store = new InMemorySessionStore();
        reservation = new RecordingReservationPort();
        CpmTokenGenerator generator =
                new CpmTokenGenerator(new LocalPrepareTokenIssuer(), 60);
        service = new CpmGenerateService(new CpmSchemeResolver("KR"), generator, store, reservation);
    }

    @Test
    void happyPathIssuesAndPersists() {
        CpmToken token = service.createSession(null, "inbound", "cust", "REF-1", "KR", null, null);

        assertEquals("ZEROPAY", token.schemeId());
        assertTrue(token.prepareToken().startsWith("ZP-CPM-"));
        assertEquals(1, store.saved.size());
        assertEquals(token.cpmTokenId(), store.saved.get(0).cpmTokenId());
    }

    @Test
    void localInboundDoesNotReserve() {
        service.createSession(null, "inbound", "cust", "REF-LOCAL", "KR", new BigDecimal("5"), 9L);
        assertTrue(reservation.reserves.isEmpty(), "inbound must not reserve prefunding");
        assertNull(store.lastReservation);
    }

    @Test
    void overseasReservesAtGenerateWithCpmTokenIdAsKey() {
        CpmToken token =
                service.createSession(null, "outbound", "cust", "REF-O", "KR", new BigDecimal("42.00"), 7L);

        assertEquals(1, reservation.reserves.size());
        var r = reservation.reserves.get(0);
        assertEquals(7L, r.partnerId());
        assertEquals(0, new BigDecimal("42.00").compareTo(r.amountUsd()));
        assertEquals(token.cpmTokenId(), r.idempotencyKey(), "idempotency key must be the CPM token id");
        // reservation handle persisted on the session
        assertNotNull(store.lastReservation);
        assertEquals(7L, store.lastReservation.partnerId());
    }

    @Test
    void overseasOverdrawMapsToInsufficientPrefunding402() {
        reservation.overdraw = true;
        QRParseException ex = assertThrows(QRParseException.class,
                () -> service.createSession(null, "outbound", "cust", "REF-OD", "KR",
                        new BigDecimal("1000"), 7L));
        assertEquals(QRErrorCode.INSUFFICIENT_PREFUNDING, ex.getErrorCode());
        assertTrue(store.saved.isEmpty(), "no session persisted when reserve overdraws");
    }

    @Test
    void overseasWithoutAmountRejected() {
        QRParseException ex = assertThrows(QRParseException.class,
                () -> service.createSession(null, "outbound", "cust", "REF-NA", "KR", null, 7L));
        assertEquals(QRErrorCode.INSUFFICIENT_PREFUNDING, ex.getErrorCode());
    }

    @Test
    void duplicatePartnerTxnRefRejectedBeforePersist() {
        store.usedRefs.add("REF-DUP");
        assertThrows(DuplicatePartnerTxnRefException.class,
                () -> service.createSession(null, "inbound", "cust", "REF-DUP", "KR", null, null));
        assertTrue(store.saved.isEmpty());
    }

    @Test
    void noSchemeForCountryRejectedBeforePersist() {
        QRParseException ex = assertThrows(QRParseException.class,
                () -> service.createSession(null, "inbound", "cust", "REF-2", "US", null, null));
        assertEquals(QRErrorCode.NO_SCHEME_FOR_LOCATION, ex.getErrorCode());
        assertTrue(store.saved.isEmpty());
    }

    /** Records reserve calls; can simulate a 402 overdraw. */
    private static final class RecordingReservationPort implements PrefundingReservationPort {
        record Call(long partnerId, BigDecimal amountUsd, String idempotencyKey, String txnRef) {}
        final List<Call> reserves = new ArrayList<>();
        final List<String> releases = new ArrayList<>();
        boolean overdraw;

        @Override
        public Reservation reserve(long partnerId, BigDecimal amountUsd, String key, String txnRef) {
            reserves.add(new Call(partnerId, amountUsd, key, txnRef));
            if (overdraw) {
                throw new QRParseException(QRErrorCode.INSUFFICIENT_PREFUNDING, "overdraw");
            }
            return new Reservation("RSV-1", amountUsd);
        }

        @Override
        public void release(long partnerId, String reservationId, String key, String reason) {
            releases.add(key);
        }
    }

    /** Minimal in-memory {@link CpmSessionStorePort} for orchestration assertions. */
    private static final class InMemorySessionStore implements CpmSessionStorePort {
        final Set<String> usedRefs = new HashSet<>();
        final List<CpmToken> saved = new ArrayList<>();
        PrefundReservation lastReservation;

        @Override public boolean existsByPartnerTxnRef(String ref) { return usedRefs.contains(ref); }

        @Override public void save(CpmToken token, String direction, String countryCode,
                                   String customerRef, boolean schemeIssued,
                                   PrefundReservation reservation) {
            usedRefs.add(token.partnerTxnRef());
            saved.add(token);
            lastReservation = reservation;
        }

        @Override public Optional<CpmToken> findByPaymentId(String paymentId) {
            return saved.stream().filter(t -> t.paymentId().equals(paymentId)).findFirst();
        }

        @Override public List<ExpiredSession> expireOverdue(Instant cutoff) { return List.of(); }
    }
}
