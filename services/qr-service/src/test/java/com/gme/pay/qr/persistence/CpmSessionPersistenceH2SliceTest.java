package com.gme.pay.qr.persistence;

import com.gme.pay.qr.domain.cpm.CpmSessionStorePort.ExpiredSession;
import com.gme.pay.qr.domain.cpm.CpmSessionStorePort.PrefundReservation;
import com.gme.pay.qr.domain.cpm.CpmToken;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H2 (PostgreSQL mode) persistence slice for the CPM prepare-session store (WBS 5.3-T01/T10).
 * Flyway applies V001-V004 from the H2-PG-mode datasource in application.properties.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaCpmSessionStoreAdapter.class)
class CpmSessionPersistenceH2SliceTest {

    @Autowired
    private JpaCpmSessionStoreAdapter store;

    @Autowired
    private CpmPrepareSessionRepository repository;

    @Test
    void saveThenFindByPaymentId() {
        CpmToken token = token("TOK-1", "PMT-1", "REF-1", Instant.now().plusSeconds(60));

        store.save(token, "inbound", "KR", "cust", false, null);

        Optional<CpmToken> found = store.findByPaymentId("PMT-1");
        assertTrue(found.isPresent());
        assertEquals("TOK-1", found.get().cpmTokenId());
        assertEquals("REF-1", found.get().partnerTxnRef());
        assertEquals("ISSUED", repository.findById("TOK-1").orElseThrow().getStatus());
    }

    @Test
    void overseasReservationPersistedAndReturnedOnExpiry() {
        Instant past = Instant.now().minus(1, ChronoUnit.MINUTES);
        store.save(token("TOK-RSV", "PMT-RSV", "REF-RSV", past),
                "outbound", "KR", "cust", true,
                new PrefundReservation(77L, "RSV-9", new BigDecimal("12.50")));

        var row = repository.findById("TOK-RSV").orElseThrow();
        assertEquals(77L, row.getPrefundPartnerId());
        assertEquals("RSV-9", row.getPrefundReservationId());
        assertEquals(0, new BigDecimal("12.50").compareTo(row.getPrefundReservedUsd()));

        List<ExpiredSession> expired = store.expireOverdue(Instant.now());
        assertEquals(1, expired.size());
        assertEquals(new ExpiredSession("TOK-RSV", 77L, "RSV-9"), expired.get(0));
    }

    @Test
    void duplicatePartnerTxnRefDetected() {
        store.save(token("TOK-2", "PMT-2", "REF-DUP", Instant.now().plusSeconds(60)),
                "inbound", "KR", "cust", false, null);

        assertTrue(store.existsByPartnerTxnRef("REF-DUP"));
        assertFalse(store.existsByPartnerTxnRef("REF-OTHER"));
    }

    @Test
    void expireOverdueMarksOnlyPastIssuedTokens() {
        Instant now = Instant.now();
        store.save(token("TOK-EXP", "PMT-EXP", "REF-EXP", now.minus(1, ChronoUnit.MINUTES)),
                "inbound", "KR", "cust", false, null);
        store.save(token("TOK-LIVE", "PMT-LIVE", "REF-LIVE", now.plus(5, ChronoUnit.MINUTES)),
                "inbound", "KR", "cust", false, null);

        List<ExpiredSession> expired = store.expireOverdue(now);

        assertEquals(List.of("TOK-EXP"), expired.stream().map(ExpiredSession::cpmTokenId).toList());
        assertEquals("EXPIRED", repository.findById("TOK-EXP").orElseThrow().getStatus());
        assertEquals("ISSUED", repository.findById("TOK-LIVE").orElseThrow().getStatus());
    }

    @Test
    void expireOverdueIsIdempotent() {
        Instant now = Instant.now();
        store.save(token("TOK-I", "PMT-I", "REF-I", now.minus(1, ChronoUnit.MINUTES)),
                "inbound", "KR", "cust", false, null);

        assertEquals(List.of("TOK-I"),
                store.expireOverdue(now).stream().map(ExpiredSession::cpmTokenId).toList());
        // second sweep finds nothing still ISSUED+overdue
        assertTrue(store.expireOverdue(now).isEmpty());
    }

    private static CpmToken token(String id, String paymentId, String ref, Instant expiresAt) {
        Instant issued = expiresAt.minus(60, ChronoUnit.SECONDS);
        return new CpmToken(id, paymentId, "ZP-CPM-" + id, "QR:" + id,
                "ZEROPAY", ref, issued, expiresAt);
    }
}
