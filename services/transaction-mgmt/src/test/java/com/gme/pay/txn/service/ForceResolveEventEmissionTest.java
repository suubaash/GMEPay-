package com.gme.pay.txn.service;

import com.gme.pay.contracts.events.PaymentReversedPayload;
import com.gme.pay.events.DomainEvent;
import com.gme.pay.events.EventPublisher;
import com.gme.pay.txn.domain.model.Transaction;
import com.gme.pay.txn.domain.model.TransactionStatus;
import com.gme.pay.txn.domain.statemachine.TransactionStateMachine;
import com.gme.pay.txn.outbox.PaymentApprovedEvent;
import com.gme.pay.txn.outbox.PaymentReversedEvent;
import com.gme.pay.txn.outbox.TransactionCommittedEvent;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies defect #1 fix: an operator force-resolve of an UNCERTAIN transaction now emits the
 * revenue-bearing / prefund-release domain events on the money-terminal outcome — not only the
 * internal FSM {@link TransactionStatusChangedEvent}.
 *
 * <ul>
 *   <li>REVERSED → {@link PaymentReversedEvent} (topic {@code gmepay.payment.reversed}) carrying
 *       {@code reversedUsd} (the prefund USD held at UNCERTAIN) + {@code reason} + {@code source=OPERATOR}.</li>
 *   <li>COMPLETED → the SAME revenue-bearing events a normal APPROVED commit emits
 *       ({@link PaymentApprovedEvent} + {@link TransactionCommittedEvent}).</li>
 * </ul>
 *
 * In-memory fake repo, real state machine, capturing publisher — no Spring / broker.
 */
class ForceResolveEventEmissionTest {

    private static final BigDecimal HELD_USD = new BigDecimal("33.88000000");

    private final List<DomainEvent> published = new ArrayList<>();
    private FakeRepo repo;
    private TransactionService service;

    @BeforeEach
    void setUp() {
        published.clear();
        repo = new FakeRepo();
        service = new TransactionService(repo, new TransactionStateMachine(published::add));
    }

    /** Seeds a V003 txn driven to UNCERTAIN with a prefund USD held (the reversible float). */
    private String seedUncertainWithPrefund() {
        Transaction txn = new Transaction(
                700L, "PARTNER-TXN-1", "zeropay", "INBOUND", "MPM",
                new BigDecimal("45000"), "KRW", new BigDecimal("33.88"), "USD", "M-1", "Q-1");
        // Held prefund USD (captured at scheme dispatch) — released on REVERSED.
        txn.applyStatusPatch("SCH-1", "AP-1", HELD_USD, Instant.now(), null, null, null);
        repo.save(txn);
        String ref = txn.txnRef();
        service.toPendingDebit(ref);
        service.toSchemeSent(ref);
        service.toUncertain(ref);
        published.clear(); // isolate the resolve-time emissions
        return ref;
    }

    @Test
    @DisplayName("operator→REVERSED publishes payment.reversed with reversedUsd + reason + source=OPERATOR")
    void reversedPublishesPaymentReversed() {
        String ref = seedUncertainWithPrefund();

        service.resolveByOperator(ref, "REVERSED", "scheme confirmed no-pay", "op.kai");

        PaymentReversedEvent reversed = published.stream()
                .filter(PaymentReversedEvent.class::isInstance)
                .map(PaymentReversedEvent.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no payment.reversed event published"));

        assertEquals(PaymentReversedPayload.EVENT_TYPE, reversed.eventType());
        assertEquals(ref, reversed.aggregateId());
        assertEquals("700", reversed.partnerId());
        assertEquals("zeropay", reversed.schemeId());
        // reversedAmount/currency = the collection-leg amount + currency from the txn snapshot.
        assertEquals("33.88", reversed.reversedAmount());
        assertEquals("USD", reversed.currency());
        assertEquals(HELD_USD.toPlainString(), reversed.reversedUsd(),
                "reversedUsd must be the prefund USD held at UNCERTAIN so prefunding can release it");
        assertEquals("scheme confirmed no-pay", reversed.reason());
        assertEquals(PaymentReversedEvent.SOURCE_OPERATOR, reversed.source());
        assertNotNull(reversed.occurredAt());

        // The FSM status event is still emitted (additive).
        assertTrue(published.stream().anyMatch(TransactionStatusChangedEvent.class::isInstance),
                "the FSM status-changed event must still be emitted alongside payment.reversed");

        // Wire payload mirrors the canonical contract.
        PaymentReversedPayload payload = reversed.toPayload();
        assertEquals(PaymentReversedPayload.EVENT_TYPE, payload.eventType());
        assertEquals(HELD_USD.toPlainString(), payload.reversedUsd());
        assertEquals("scheme confirmed no-pay", payload.reason());
    }

    @Test
    @DisplayName("operator→COMPLETED publishes the revenue-bearing events a normal commit emits")
    void completedPublishesRevenueBearingEvents() {
        String ref = seedUncertainWithPrefund();

        service.resolveByOperator(ref, "COMPLETED", "manual recon: scheme paid", "op.kai");

        // Same shape as a normal APPROVED commit: payment.approved (webhook/revenue) + transaction.committed.
        PaymentApprovedEvent approved = published.stream()
                .filter(PaymentApprovedEvent.class::isInstance)
                .map(PaymentApprovedEvent.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no payment.approved event on operator COMPLETED"));
        assertEquals(ref, approved.txnRef());
        assertEquals(700L, approved.partnerId());
        assertEquals(TransactionStatus.APPROVED, approved.toStatus());

        assertTrue(published.stream().anyMatch(TransactionCommittedEvent.class::isInstance),
                "operator COMPLETED must emit transaction.committed so revenue-ledger recognises revenue");

        // No reversal event on the COMPLETED path.
        assertTrue(published.stream().noneMatch(PaymentReversedEvent.class::isInstance));
    }

    @Test
    @DisplayName("idempotent repeat REVERSED emits no second payment.reversed")
    void idempotentReversalEmitsOnce() {
        String ref = seedUncertainWithPrefund();
        service.resolveByOperator(ref, "REVERSED", "reason", "op.kai");
        long afterFirst = published.stream().filter(PaymentReversedEvent.class::isInstance).count();

        service.resolveByOperator(ref, "REVERSED", "reason", "op.kai");
        long afterSecond = published.stream().filter(PaymentReversedEvent.class::isInstance).count();

        assertEquals(1, afterFirst);
        assertEquals(afterFirst, afterSecond, "idempotent replay must not re-emit payment.reversed");
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
                                                         Pageable pageable) {
            return Page.empty(pageable);
        }
        @Override public List<Transaction> findExpiredNonTerminal(Instant expiryBefore) { return List.of(); }
        @Override public List<Transaction> findStuck(Instant stuckBefore, List<String> sweepStatuses) { return List.of(); }
        @Override public List<Transaction> findCommittedFx(LocalDate from, LocalDate to, Long partnerId) { return List.of(); }
        @Override public List<Transaction> findRefundedOn(LocalDate refundedOn) { return List.of(); }
    }
}
