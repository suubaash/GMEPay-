package com.gme.pay.qr.domain.cpm;

import com.gme.pay.qr.exception.DuplicatePartnerTxnRefException;
import com.gme.pay.qr.exception.QRErrorCode;
import com.gme.pay.qr.exception.QRParseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
    private CpmGenerateService service;

    @BeforeEach
    void setUp() {
        store = new InMemorySessionStore();
        CpmTokenGenerator generator =
                new CpmTokenGenerator(new LocalPrepareTokenIssuer(), 60);
        service = new CpmGenerateService(new CpmSchemeResolver("KR"), generator, store);
    }

    @Test
    void happyPathIssuesAndPersists() {
        CpmToken token = service.createSession(null, "inbound", "cust", "REF-1", "KR");

        assertEquals("ZEROPAY", token.schemeId());
        assertTrue(token.prepareToken().startsWith("ZP-CPM-"));
        assertEquals(1, store.saved.size());
        assertEquals(token.cpmTokenId(), store.saved.get(0).cpmTokenId());
    }

    @Test
    void duplicatePartnerTxnRefRejectedBeforePersist() {
        store.usedRefs.add("REF-DUP");
        assertThrows(DuplicatePartnerTxnRefException.class,
                () -> service.createSession(null, "inbound", "cust", "REF-DUP", "KR"));
        assertTrue(store.saved.isEmpty());
    }

    @Test
    void noSchemeForCountryRejectedBeforePersist() {
        QRParseException ex = assertThrows(QRParseException.class,
                () -> service.createSession(null, "inbound", "cust", "REF-2", "US"));
        assertEquals(QRErrorCode.NO_SCHEME_FOR_LOCATION, ex.getErrorCode());
        assertTrue(store.saved.isEmpty());
    }

    /** Minimal in-memory {@link CpmSessionStorePort} for orchestration assertions. */
    private static final class InMemorySessionStore implements CpmSessionStorePort {
        final Set<String> usedRefs = new HashSet<>();
        final List<CpmToken> saved = new ArrayList<>();

        @Override public boolean existsByPartnerTxnRef(String ref) { return usedRefs.contains(ref); }

        @Override public void save(CpmToken token, String direction, String countryCode,
                                   String customerRef, boolean schemeIssued) {
            usedRefs.add(token.partnerTxnRef());
            saved.add(token);
        }

        @Override public Optional<CpmToken> findByPaymentId(String paymentId) {
            return saved.stream().filter(t -> t.paymentId().equals(paymentId)).findFirst();
        }

        @Override public List<String> expireOverdue(Instant cutoff) { return List.of(); }
    }
}
