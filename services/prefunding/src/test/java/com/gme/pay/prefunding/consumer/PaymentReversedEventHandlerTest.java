package com.gme.pay.prefunding.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.gme.pay.prefunding.persistence.LedgerEntryRepository;
import com.gme.pay.prefunding.persistence.PartnerBalanceEntity;
import com.gme.pay.prefunding.persistence.PartnerBalanceRepository;
import com.gme.pay.prefunding.service.PrefundingService;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * #1 release-on-reversal (H2 + real service, no broker): the {@code payment.reversed} handler credits
 * the held prefund float back for {@code reversedUsd}, keyed idempotently on {@code txnRef}, and is safe
 * for an unknown partner / null reversedUsd.
 */
@SpringBootTest(properties = "gmepay.outbox.poll-ms=3600000")
@ActiveProfiles("test")
class PaymentReversedEventHandlerTest {

    private static final String PARTNER = "REV_P1";

    @Autowired private PaymentReversedEventHandler handler;
    @Autowired private PrefundingService service;
    @Autowired private PartnerBalanceRepository balances;
    @Autowired private LedgerEntryRepository ledger;

    @BeforeEach
    void cleanSlate() {
        ledger.deleteAll();
        balances.deleteAll();
        balances.save(new PartnerBalanceEntity(PARTNER, "USD", new BigDecimal("1000.0000"),
                new BigDecimal("5000.0000"), Instant.now()));
    }

    private String event(String txnRef, String partnerId, String reversedUsd) {
        // reversedUsd null → the JSON literal null; otherwise a quoted decimal string (money convention).
        String usd = reversedUsd == null ? "null" : "\"" + reversedUsd + "\"";
        return "{\"eventType\":\"payment.reversed\",\"txnRef\":\"" + txnRef + "\",\"partnerId\":\""
                + partnerId + "\",\"schemeId\":\"S1\",\"reversedAmount\":\"1.00\",\"currency\":\"KRW\","
                + "\"reversedUsd\":" + usd + ",\"reason\":\"op force-resolve\",\"source\":\"OPERATOR\","
                + "\"occurredAt\":\"2026-07-02T00:00:00Z\"}";
    }

    @Test
    @DisplayName("payment.reversed credits the held USD back to the partner")
    void reversal_releasesHeldFloat() {
        BigDecimal released = handler.handle("k", event("txn-R1", PARTNER, "120.0000"));

        assertEquals(0, released.compareTo(new BigDecimal("120.0000")));
        assertEquals(0, service.getBalance(PARTNER).compareTo(new BigDecimal("1120.0000")),
                "held float returned onto balance");
    }

    @Test
    @DisplayName("redelivery of the same txnRef does NOT double-credit (idempotent)")
    void reversal_isIdempotentOnTxnRef() {
        handler.handle("k", event("txn-R2", PARTNER, "50.0000"));
        BigDecimal second = handler.handle("k", event("txn-R2", PARTNER, "50.0000"));

        assertEquals(0, second.compareTo(BigDecimal.ZERO), "replay releases nothing");
        assertEquals(0, service.getBalance(PARTNER).compareTo(new BigDecimal("1050.0000")),
                "credited exactly once across two deliveries");
    }

    @Test
    @DisplayName("null reversedUsd is a safe no-op (nothing to release)")
    void reversal_nullReversedUsd_isNoOp() {
        BigDecimal released = handler.handle("k", event("txn-R3", PARTNER, null));

        assertEquals(0, released.compareTo(BigDecimal.ZERO));
        assertEquals(0, service.getBalance(PARTNER).compareTo(new BigDecimal("1000.0000")));
    }

    @Test
    @DisplayName("unknown partner surfaces a validation error (consumer decides ack/DLT)")
    void reversal_unknownPartner_throws() {
        assertThrows(RuntimeException.class,
                () -> handler.handle("k", event("txn-R4", "NOPE", "10.0000")));
    }

    @Test
    @DisplayName("missing txnRef is poison (IllegalArgumentException → DLT path)")
    void reversal_missingTxnRef_isPoison() {
        String bad = "{\"eventType\":\"payment.reversed\",\"partnerId\":\"" + PARTNER
                + "\",\"reversedUsd\":\"10.00\"}";
        assertThrows(IllegalArgumentException.class, () -> handler.handle("k", bad));
    }
}
