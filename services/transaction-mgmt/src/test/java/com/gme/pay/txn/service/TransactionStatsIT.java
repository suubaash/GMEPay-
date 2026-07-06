package com.gme.pay.txn.service;

import com.gme.pay.txn.api.dto.TransactionStatsResponse;
import com.gme.pay.txn.domain.statemachine.TransactionStateMachine;
import com.gme.pay.events.DomainEvent;
import com.gme.pay.txn.persistence.TransactionEntity;
import com.gme.pay.txn.persistence.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * Delivery-dashboard stats math ({@link TransactionService#computeStats}) exercised against the
 * REAL grouped SQL over H2 (PostgreSQL mode) + Flyway, seeding a mix of partners / corridors /
 * statuses / failure reasons and asserting the totals, per-partner / per-corridor slices, decline
 * reasons and the first-approved activation signal.
 *
 * <p>No Docker, no Testcontainers; plain JUnit 5 + {@code @DataJpaTest}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true"
})
class TransactionStatsIT {

    @Autowired
    private TransactionRepository jpaRepository;

    private TransactionService service;

    // A fixed window; all seed rows are created inside [WINDOW_FROM, WINDOW_TO).
    private static final Instant WINDOW_FROM = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant WINDOW_TO   = Instant.parse("2026-07-01T00:00:00Z");
    private static final Instant IN_WINDOW   = Instant.parse("2026-06-15T10:00:00Z");

    @BeforeEach
    void setUp() {
        service = new TransactionService(
                new InMemoryTransactionRepository(jpaRepository),
                new TransactionStateMachine(new ArrayList<DomainEvent>()::add));
        jpaRepository.deleteAll();
        jpaRepository.flush();
    }

    private void seed(String txnRef, String partnerRef, String schemeId,
                      String status, String failureReason, Instant createdAt) {
        TransactionEntity e = new TransactionEntity();
        e.setTxnRef(txnRef);
        e.setPartnerRef(partnerRef);
        e.setSchemeId(schemeId);
        e.setStatus(status);
        e.setFailureReason(failureReason);
        e.setSendAmount(new BigDecimal("100.00000000"));
        e.setSendCcy("USD");
        e.setTargetPayout(new BigDecimal("130000.00000000"));
        e.setTargetCcy("KRW");
        e.setCreatedAt(createdAt);
        e.setUpdatedAt(createdAt);
        jpaRepository.save(e);
    }

    private void seedWithPayer(String txnRef, String status, String userRef, Instant createdAt) {
        TransactionEntity e = new TransactionEntity();
        e.setTxnRef(txnRef);
        e.setPartnerRef("partner-A");
        e.setSchemeId("zeropay");
        e.setStatus(status);
        e.setUserRef(userRef);
        e.setSendAmount(new BigDecimal("100.00000000"));
        e.setSendCcy("USD");
        e.setTargetPayout(new BigDecimal("130000.00000000"));
        e.setTargetCcy("KRW");
        e.setCreatedAt(createdAt);
        e.setUpdatedAt(createdAt);
        jpaRepository.save(e);
    }

    @Test
    @DisplayName("computePayerStats counts distinct APPROVED payers only, in-window, null user_ref excluded")
    void computePayerStats_math() {
        seedWithPayer("P1", "APPROVED", "payer-1", IN_WINDOW);
        seedWithPayer("P2", "APPROVED", "payer-1", IN_WINDOW);   // same payer twice -> 1
        seedWithPayer("P3", "APPROVED", "payer-2", IN_WINDOW);
        seedWithPayer("P4", "FAILED",   "payer-3", IN_WINDOW);   // not APPROVED -> excluded
        seedWithPayer("P5", "APPROVED", null,      IN_WINDOW);   // no user_ref -> excluded
        seedWithPayer("P6", "APPROVED", "payer-4",
                Instant.parse("2026-05-01T00:00:00Z"));           // out of window -> excluded

        com.gme.pay.txn.api.dto.PayerStatsResponse stats =
                service.computePayerStats(WINDOW_FROM, WINDOW_TO);

        assertEquals(2, stats.activePayers(), "payer-1 (deduped) + payer-2");
        assertEquals(WINDOW_FROM, stats.window().from());
        assertEquals(WINDOW_TO, stats.window().to());
    }

    @Test
    @DisplayName("computeStats returns correct totals, per-partner/corridor slices and decline reasons")
    void computeStats_math() {
        // partner-A on zeropay: 3 APPROVED, 1 FAILED(APPROVAL_TIMEOUT), 1 CANCELLED(null reason)
        seed("A1", "partner-A", "zeropay", "APPROVED", null, IN_WINDOW);
        seed("A2", "partner-A", "zeropay", "APPROVED", null, IN_WINDOW);
        seed("A3", "partner-A", "zeropay", "APPROVED", null, IN_WINDOW);
        seed("A4", "partner-A", "zeropay", "FAILED", "APPROVAL_TIMEOUT", IN_WINDOW);
        seed("A5", "partner-A", "zeropay", "CANCELLED", null, IN_WINDOW);
        // partner-B on alipay: 1 APPROVED, 1 FAILED(INSUFFICIENT_FUNDS)
        seed("B1", "partner-B", "alipay", "APPROVED", null, IN_WINDOW);
        seed("B2", "partner-B", "alipay", "FAILED", "INSUFFICIENT_FUNDS", IN_WINDOW);
        // A non-terminal CREATED row: counts toward total but is neither approved nor declined.
        seed("C1", "partner-B", "alipay", "CREATED", null, IN_WINDOW);
        // Out of window — must be excluded from stats.
        seed("Z1", "partner-A", "zeropay", "APPROVED", null, Instant.parse("2026-05-01T00:00:00Z"));

        TransactionStatsResponse stats = service.computeStats(WINDOW_FROM, WINDOW_TO);

        // Totals: 8 in window (A1-A5, B1, B2, C1). approved=4 (A1,A2,A3,B1). declined=3 (A4,A5,B2).
        assertEquals(8, stats.totals().total());
        assertEquals(4, stats.totals().approved());
        assertEquals(3, stats.totals().declined());
        // 4/8 = 50.0
        assertEquals(50.0, stats.totals().successRatePct(), 0.001);

        // Per-partner
        var pA = stats.byPartner().stream().filter(p -> p.partner().equals("partner-A")).findFirst().orElseThrow();
        assertEquals(5, pA.total());
        assertEquals(3, pA.approved());
        assertEquals(2, pA.declined());
        assertEquals(60.0, pA.successRatePct(), 0.001);   // 3/5

        var pB = stats.byPartner().stream().filter(p -> p.partner().equals("partner-B")).findFirst().orElseThrow();
        assertEquals(3, pB.total());
        assertEquals(1, pB.approved());
        assertEquals(1, pB.declined());
        assertEquals(33.3, pB.successRatePct(), 0.001);   // 1/3 = 33.333 -> 33.3

        // Per-corridor (= scheme_id)
        var zp = stats.byCorridor().stream().filter(c -> c.corridor().equals("zeropay")).findFirst().orElseThrow();
        assertEquals(5, zp.total());
        assertEquals(3, zp.approved());
        var ap = stats.byCorridor().stream().filter(c -> c.corridor().equals("alipay")).findFirst().orElseThrow();
        assertEquals(3, ap.total());
        assertEquals(1, ap.approved());

        // Decline reasons: APPROVAL_TIMEOUT x1, INSUFFICIENT_FUNDS x1, CANCELLED (null reason -> status) x1
        Map<String, Long> reasons = new java.util.HashMap<>();
        stats.declineReasons().forEach(r -> reasons.put(r.reason(), r.count()));
        assertEquals(1L, reasons.get("APPROVAL_TIMEOUT"));
        assertEquals(1L, reasons.get("INSUFFICIENT_FUNDS"));
        assertEquals(1L, reasons.get("CANCELLED"));
        assertEquals(3, reasons.size());
    }

    @Test
    @DisplayName("successRatePct is 0 when the window has no transactions")
    void computeStats_emptyWindow() {
        TransactionStatsResponse stats = service.computeStats(WINDOW_FROM, WINDOW_TO);
        assertEquals(0, stats.totals().total());
        assertEquals(0.0, stats.totals().successRatePct(), 0.001);
        assertTrue(stats.byPartner().isEmpty());
        assertTrue(stats.declineReasons().isEmpty());
    }

    @Test
    @DisplayName("firstApprovedByPartner returns the earliest APPROVED createdAt per partner")
    void firstApproved_perPartner() {
        seed("A-late", "partner-A", "zeropay", "APPROVED", null, Instant.parse("2026-06-20T00:00:00Z"));
        seed("A-early", "partner-A", "zeropay", "APPROVED", null, Instant.parse("2026-06-10T00:00:00Z"));
        seed("A-failed", "partner-A", "zeropay", "FAILED", "X", Instant.parse("2026-06-01T00:00:00Z"));
        seed("B-only", "partner-B", "alipay", "CREATED", null, IN_WINDOW);   // never approved

        Map<String, Instant> firstApproved = service.firstApprovedByPartner();
        assertEquals(Instant.parse("2026-06-10T00:00:00Z"), firstApproved.get("partner-A"));
        assertNull(firstApproved.get("partner-B"), "partner-B never approved -> no entry");
    }
}
