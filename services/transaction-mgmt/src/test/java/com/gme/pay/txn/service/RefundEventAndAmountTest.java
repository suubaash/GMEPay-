package com.gme.pay.txn.service;

import com.gme.pay.contracts.events.PaymentReversedPayload;
import com.gme.pay.events.DomainEvent;
import com.gme.pay.txn.domain.model.Transaction;
import com.gme.pay.txn.domain.model.TransactionStatus;
import com.gme.pay.txn.domain.statemachine.TransactionStateMachine;
import com.gme.pay.txn.outbox.PaymentReversedEvent;
import com.gme.pay.txn.outbox.TransactionStatusChangedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gap T2-6, transaction-mgmt side. Two defects, both of which made a refund a silent event:
 *
 * <ol>
 *   <li><b>{@code refundAmountKrw} was never populated.</b> The PATCH contract carried no refund-amount
 *       field, so the refund path re-sent the row's existing (always null) value and
 *       {@code transactions.refund_amount_krw} stayed empty forever. Settlement's cross-date claw-back reads
 *       exactly that column, so it netted zero — the merchant was never debited back for a refund.</li>
 *   <li><b>{@code REFUNDED} emitted no domain event.</b> The state machine published
 *       {@code payment.reversed} only for {@code REVERSED}, so revenue-ledger's reversal handler never ran
 *       for a refund and captured revenue stayed on the books after the money went back.</li>
 * </ol>
 *
 * In-memory fake repo, real state machine, capturing publisher — no Spring, no broker.
 */
class RefundEventAndAmountTest {

    private static final BigDecimal CAPTURED_USD = new BigDecimal("37.50000000");

    private final List<DomainEvent> published = new ArrayList<>();
    private FakeRepo repo;
    private TransactionService service;

    @BeforeEach
    void setUp() {
        published.clear();
        repo = new FakeRepo();
        service = new TransactionService(repo, new TransactionStateMachine(published::add));
    }

    /** Seeds an APPROVED KRW-collected transaction with prefund USD recorded against it. */
    private String seedApproved() {
        Transaction txn = new Transaction(
                700L, "PARTNER-TXN-1", "zeropay", "INBOUND", "MPM",
                new BigDecimal("50000"), "KRW", new BigDecimal("50000"), "KRW", "M-1", "Q-1");
        repo.save(txn);
        String ref = txn.txnRef();
        service.patchStatus(ref, "APPROVED", "SCH-1", "AP-1", CAPTURED_USD, Instant.now(),
                null, null, null, null, null, null, null, null);
        published.clear();  // isolate the refund-time emissions
        return ref;
    }

    // ======================================================================
    // Defect: refundAmountKrw never populated
    // ======================================================================

    @Test
    @DisplayName("the REFUNDED patch persists refundAmountKrw, so the claw-back has a magnitude to net")
    void refundedPatch_persistsRefundAmountKrw() {
        String ref = seedApproved();

        Transaction refunded = service.patchStatus(ref, "REFUNDED", "SCH-1", "AP-1",
                new BigDecimal("15.00000000"), null, null, null, null, null, null, null, null, null,
                new BigDecimal("20000"));

        assertEquals(TransactionStatus.REFUNDED, refunded.status());
        assertEquals(0, new BigDecimal("20000").compareTo(refunded.refundAmountKrw()),
                "the amount the settlement claw-back nets on is now on the row");
        assertNotNull(refunded.refundedAt(), "REFUNDED stamps the refund date the refund query filters on");
    }

    @Test
    @DisplayName("originalPaymentTxnRef is defaulted so the claw-back's netting key is not blank")
    void refundedPatch_defaultsTheNettingKey() {
        String ref = seedApproved();

        Transaction refunded = service.patchStatus(ref, "REFUNDED", "SCH-1", "AP-1", null, null,
                null, null, null, null, null, null, null, null, new BigDecimal("50000"));

        // settlement's isCrossDateClawbackEligible REJECTS any leg whose original-payment ref is blank. A
        // refund is recorded ON the original row today, so the leg's original payment IS itself — which is
        // the second reason the claw-back netted nothing even where an amount existed.
        assertEquals(ref, refunded.originalPaymentTxnRef());
    }

    @Test
    @DisplayName("a refunded transaction is found by findRefundedOn WITH its amount")
    void refundedTransaction_isDiscoverableWithItsAmount() {
        String ref = seedApproved();
        service.patchStatus(ref, "REFUNDED", "SCH-1", "AP-1", null, null,
                null, null, null, null, null, null, null, null, new BigDecimal("20000"));

        List<Transaction> found = service.findRefundedOn(LocalDate.now(java.time.ZoneOffset.UTC));
        assertEquals(1, found.size(), "the refund must be discoverable on its refund date");
        assertEquals(0, new BigDecimal("20000").compareTo(found.get(0).refundAmountKrw()));
        assertEquals(ref, found.get(0).originalPaymentTxnRef());
    }

    @Test
    @DisplayName("a cumulative second refund raises the recorded amount rather than replacing the first")
    void secondPartialRefund_raisesTheCumulativeAmount() {
        String ref = seedApproved();
        service.patchStatus(ref, "REFUNDED", "SCH-1", "AP-1", null, null,
                null, null, null, null, null, null, null, null, new BigDecimal("20000"));

        // payment-executor sends the CUMULATIVE total, and the row is already REFUNDED (a terminal state, so
        // no second transition happens) — the amount must still be updated or settlement would under-net.
        Transaction after = service.patchStatus(ref, "REFUNDED", "SCH-1", "AP-1", null, null,
                null, null, null, null, null, null, null, null, new BigDecimal("30000"));

        assertEquals(0, new BigDecimal("30000").compareTo(after.refundAmountKrw()));
    }

    @Test
    @DisplayName("a null refundAmountKrw never clears a previously recorded amount")
    void nullRefundAmount_isSkippedNotCleared() {
        String ref = seedApproved();
        service.patchStatus(ref, "REFUNDED", "SCH-1", "AP-1", null, null,
                null, null, null, null, null, null, null, null, new BigDecimal("20000"));

        Transaction after = service.patchStatus(ref, "REFUNDED", "SCH-1", "AP-1", null, null,
                null, null, null, null, null, null, null, null, null);

        assertEquals(0, new BigDecimal("20000").compareTo(after.refundAmountKrw()),
                "an omitted field must not be read as 'set it to nothing'");
    }

    @Test
    @DisplayName("a non-refund patch is completely unaffected by the new field")
    void nonRefundPatch_isUnaffected() {
        Transaction txn = new Transaction(
                700L, "PARTNER-TXN-2", "zeropay", "INBOUND", "MPM",
                new BigDecimal("50000"), "KRW", new BigDecimal("50000"), "KRW", "M-1", "Q-1");
        repo.save(txn);

        Transaction approved = service.patchStatus(txn.txnRef(), "APPROVED", "SCH-9", "AP-9",
                CAPTURED_USD, Instant.now(), null, null, null, null, null, null, null, null);

        assertEquals(TransactionStatus.APPROVED, approved.status());
        assertNull(approved.refundAmountKrw());
        assertNull(approved.refundedAt());
    }

    // ======================================================================
    // Defect: REFUNDED emitted no domain event
    // ======================================================================

    @Test
    @DisplayName("REFUNDED emits payment.reversed with source=REFUND, the refunded amount and the refunded USD")
    void refunded_emitsPaymentReversed() {
        String ref = seedApproved();

        service.patchStatus(ref, "REFUNDED", "SCH-1", "AP-1", new BigDecimal("15.00000000"), null,
                null, null, null, null, null, null, null, null, new BigDecimal("20000"));

        PaymentReversedEvent reversed = published.stream()
                .filter(PaymentReversedEvent.class::isInstance)
                .map(PaymentReversedEvent.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "REFUNDED published no payment.reversed — revenue-ledger's reversal would never run"));

        assertEquals(PaymentReversedPayload.EVENT_TYPE, reversed.eventType());
        assertEquals(ref, reversed.aggregateId());
        assertEquals("700", reversed.partnerId());
        assertEquals(PaymentReversedEvent.SOURCE_REFUND, reversed.source(),
                "a customer refund is distinguishable from an operator force-resolve");
        // The event carries the REFUNDED amount, not the original — a partial refund must not look full.
        assertEquals("20000", reversed.reversedAmount());
        assertEquals("KRW", reversed.currency());
        assertEquals("15.00000000", reversed.reversedUsd(),
                "reversedUsd is the USD credited back, which is what prefunding releases");
        assertNotNull(reversed.occurredAt());

        // Additive: the internal FSM event is still emitted.
        assertTrue(published.stream().anyMatch(TransactionStatusChangedEvent.class::isInstance));

        // The wire payload mirrors the canonical contract (the shape consumers bind).
        PaymentReversedPayload payload = reversed.toPayload();
        assertEquals(PaymentReversedPayload.EVENT_TYPE, payload.eventType());
        assertEquals("20000", payload.reversedAmount());
        assertEquals(PaymentReversedEvent.SOURCE_REFUND, payload.source());
    }

    @Test
    @DisplayName("a refund with no recorded amount still emits the event, falling back to the full collection")
    void refundedWithoutAmount_stillEmitsWithTheCollectionAmount() {
        String ref = seedApproved();

        service.patchStatus(ref, "REFUNDED", "SCH-1", "AP-1", null, null,
                null, null, null, null, null, null, null, null, null);

        PaymentReversedEvent reversed = published.stream()
                .filter(PaymentReversedEvent.class::isInstance)
                .map(PaymentReversedEvent.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no payment.reversed event"));
        assertEquals("50000", reversed.reversedAmount(), "falls back to the whole collection amount");
        assertEquals("KRW", reversed.currency());
    }

    @Test
    @DisplayName("a repeated REFUNDED patch emits no second event (terminal state, no self-edge)")
    void repeatedRefundPatch_emitsOnce() {
        String ref = seedApproved();
        service.patchStatus(ref, "REFUNDED", "SCH-1", "AP-1", null, null,
                null, null, null, null, null, null, null, null, new BigDecimal("20000"));
        long afterFirst = published.stream().filter(PaymentReversedEvent.class::isInstance).count();

        service.patchStatus(ref, "REFUNDED", "SCH-1", "AP-1", null, null,
                null, null, null, null, null, null, null, null, new BigDecimal("30000"));
        long afterSecond = published.stream().filter(PaymentReversedEvent.class::isInstance).count();

        assertEquals(1, afterFirst);
        assertEquals(afterFirst, afterSecond,
                "REFUNDED is terminal, so the amount is updated without re-emitting; every consumer of "
                        + "payment.reversed is idempotent per txnRef and would skip a second event anyway");
    }

    private static final class FakeRepo implements TransactionRepository {
        private final Map<String, Transaction> store = new HashMap<>();

        @Override public Transaction save(Transaction txn) { store.put(txn.txnRef(), txn); return txn; }
        @Override public Optional<Transaction> findByTxnRef(String txnRef) {
            return Optional.ofNullable(store.get(txnRef));
        }
        @Override public Page<Transaction> findByFilters(LocalDate from, LocalDate to,
                                                        TransactionStatus status, Long partnerId,
                                                        String txnRef, String schemeTxnRef, String merchantId,
                                                        String userRef, String reference, String schemeId,
                                                        Pageable pageable) {
            return Page.empty(pageable);
        }
        @Override public List<Transaction> findExpiredNonTerminal(Instant expiryBefore) { return List.of(); }
        @Override public List<Transaction> findStuck(Instant stuckBefore, List<String> sweepStatuses) {
            return List.of();
        }
        @Override public List<Transaction> findCommittedFx(LocalDate from, LocalDate to, Long partnerId) {
            return List.of();
        }
        @Override public List<Transaction> findRefundedOn(LocalDate refundedOn) {
            LocalDate day = refundedOn;
            return store.values().stream()
                    .filter(t -> t.refundedAt() != null)
                    .filter(t -> t.refundedAt().atZone(java.time.ZoneOffset.UTC).toLocalDate().equals(day))
                    .toList();
        }
    }
}
