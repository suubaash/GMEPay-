package com.gme.pay.txn.statemachine;

import com.gme.pay.events.DomainEvent;
import com.gme.pay.events.EventPublisher;
import com.gme.pay.txn.domain.model.Transaction;
import com.gme.pay.txn.domain.model.TransactionStatus;
import com.gme.pay.txn.domain.statemachine.TransactionStateMachine;
import com.gme.pay.txn.domain.statemachine.TransitionBlockedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plain JUnit 5 unit tests for {@link TransactionStateMachine}.
 * No Spring context, no Docker, no Testcontainers.
 */
class TransactionStateMachineTest {

    /** Captures published events for assertion. */
    private final List<DomainEvent> publishedEvents = new ArrayList<>();

    private TransactionStateMachine stateMachine;

    @BeforeEach
    void setUp() {
        publishedEvents.clear();
        EventPublisher capturingPublisher = publishedEvents::add;
        stateMachine = new TransactionStateMachine(capturingPublisher);
    }

    private Transaction newTransaction() {
        return new Transaction("partner-1",
                new BigDecimal("100.00"), "USD",
                new BigDecimal("130000"), "KRW");
    }

    // ------------------------------------------------------------------
    // Legal transitions
    // ------------------------------------------------------------------

    @Test
    @DisplayName("CREATED → PENDING_DEBIT succeeds and publishes an event")
    void createdToPendingDebit() {
        Transaction txn = newTransaction();
        assertEquals(TransactionStatus.CREATED, txn.status());

        stateMachine.transition(txn, TransactionStatus.PENDING_DEBIT);

        assertEquals(TransactionStatus.PENDING_DEBIT, txn.status());
        assertEquals(1, publishedEvents.size());
        assertEquals("TransactionStatusChanged", publishedEvents.get(0).eventType());
        assertEquals(txn.txnRef(), publishedEvents.get(0).aggregateId());
    }

    @Test
    @DisplayName("CREATED → APPROVED on a legacy (null partnerId) txn emits only TransactionStatusChanged")
    void createdToApproved() {
        Transaction txn = newTransaction(); // legacy 5-arg ctor → partnerId == null
        stateMachine.transition(txn, TransactionStatus.APPROVED);
        assertEquals(TransactionStatus.APPROVED, txn.status());
        // No payment.approved: it is guarded out for null-partnerId txns (would be undeliverable).
        assertEquals(1, publishedEvents.size());
        assertEquals("TransactionStatusChanged", publishedEvents.get(0).eventType());
        assertTrue(publishedEvents.stream().noneMatch(e -> "payment.approved".equals(e.eventType())));
    }

    @Test
    @DisplayName("CREATED → FAILED succeeds (TTL expiry path)")
    void createdToFailed() {
        Transaction txn = newTransaction();
        stateMachine.transition(txn, TransactionStatus.FAILED);
        assertEquals(TransactionStatus.FAILED, txn.status());
    }

    @Test
    @DisplayName("CREATED → CANCELLED succeeds")
    void createdToCancelled() {
        Transaction txn = newTransaction();
        stateMachine.transition(txn, TransactionStatus.CANCELLED);
        assertEquals(TransactionStatus.CANCELLED, txn.status());
    }

    @Test
    @DisplayName("PENDING_DEBIT → APPROVED succeeds")
    void pendingDebitToApproved() {
        Transaction txn = newTransaction();
        stateMachine.transition(txn, TransactionStatus.PENDING_DEBIT);
        stateMachine.transition(txn, TransactionStatus.APPROVED);
        assertEquals(TransactionStatus.APPROVED, txn.status());
        // Legacy txn (null partnerId): PENDING_DEBIT + APPROVED status-changed only, no payment.approved = 2
        assertEquals(2, publishedEvents.size());
    }

    @Test
    @DisplayName("PENDING_DEBIT → FAILED succeeds (insufficient prefunding)")
    void pendingDebitToFailed() {
        Transaction txn = newTransaction();
        stateMachine.transition(txn, TransactionStatus.PENDING_DEBIT);
        stateMachine.transition(txn, TransactionStatus.FAILED);
        assertEquals(TransactionStatus.FAILED, txn.status());
    }

    @Test
    @DisplayName("PENDING_DEBIT → CANCELLED succeeds")
    void pendingDebitToCancelled() {
        Transaction txn = newTransaction();
        stateMachine.transition(txn, TransactionStatus.PENDING_DEBIT);
        stateMachine.transition(txn, TransactionStatus.CANCELLED);
        assertEquals(TransactionStatus.CANCELLED, txn.status());
    }

    // ------------------------------------------------------------------
    // Illegal transitions
    // ------------------------------------------------------------------

    @Test
    @DisplayName("APPROVED → FAILED is blocked (terminal state has no outgoing)")
    void approvedToFailedIsBlocked() {
        Transaction txn = newTransaction();
        stateMachine.transition(txn, TransactionStatus.APPROVED);

        TransitionBlockedException ex = assertThrows(
                TransitionBlockedException.class,
                () -> stateMachine.transition(txn, TransactionStatus.FAILED));

        assertEquals(TransactionStatus.APPROVED, ex.getFrom());
        assertEquals(TransactionStatus.FAILED,   ex.getTo());
        assertEquals(txn.txnRef(),               ex.getTxnRef());
        // Status must NOT have changed
        assertEquals(TransactionStatus.APPROVED, txn.status());
    }

    @Test
    @DisplayName("FAILED → PENDING_DEBIT is blocked (no transitions out of FAILED)")
    void failedToPendingDebitIsBlocked() {
        Transaction txn = newTransaction();
        stateMachine.transition(txn, TransactionStatus.FAILED);

        assertThrows(TransitionBlockedException.class,
                () -> stateMachine.transition(txn, TransactionStatus.PENDING_DEBIT));
        assertEquals(TransactionStatus.FAILED, txn.status());
    }

    @Test
    @DisplayName("CANCELLED → APPROVED is blocked (terminal state)")
    void cancelledToApprovedIsBlocked() {
        Transaction txn = newTransaction();
        stateMachine.transition(txn, TransactionStatus.CANCELLED);

        assertThrows(TransitionBlockedException.class,
                () -> stateMachine.transition(txn, TransactionStatus.APPROVED));
        assertEquals(TransactionStatus.CANCELLED, txn.status());
    }

    @Test
    @DisplayName("Self-transition CREATED → CREATED is blocked")
    void selfTransitionBlocked() {
        Transaction txn = newTransaction();
        assertThrows(TransitionBlockedException.class,
                () -> stateMachine.transition(txn, TransactionStatus.CREATED));
        assertEquals(TransactionStatus.CREATED, txn.status());
    }

    @Test
    @DisplayName("Backward transition PENDING_DEBIT → CREATED is blocked")
    void backwardTransitionBlocked() {
        Transaction txn = newTransaction();
        stateMachine.transition(txn, TransactionStatus.PENDING_DEBIT);

        assertThrows(TransitionBlockedException.class,
                () -> stateMachine.transition(txn, TransactionStatus.CREATED));
        assertEquals(TransactionStatus.PENDING_DEBIT, txn.status());
    }

    // ------------------------------------------------------------------
    // Event publishing details
    // ------------------------------------------------------------------

    @Test
    @DisplayName("No event is published when a transition is blocked")
    void noEventOnBlockedTransition() {
        Transaction txn = newTransaction();
        stateMachine.transition(txn, TransactionStatus.APPROVED);
        int eventCountBeforeAttempt = publishedEvents.size();

        assertThrows(TransitionBlockedException.class,
                () -> stateMachine.transition(txn, TransactionStatus.CANCELLED));

        assertEquals(eventCountBeforeAttempt, publishedEvents.size(),
                "No new event should be published for a blocked transition");
    }

    @Test
    @DisplayName("payment.approved event carries partnerId + partnerTxnRef for webhook resolution")
    void approvedEmitsPaymentApprovedWithPartnerId() {
        Transaction txn = new Transaction(
                700L, "PARTNER-TXN-9", "zeropay", "OUT", "MPM",
                new BigDecimal("130000"), "KRW", new BigDecimal("100.00"), "USD", "M-1", "Q-1");
        stateMachine.transition(txn, TransactionStatus.APPROVED);

        com.gme.pay.txn.outbox.PaymentApprovedEvent approved = publishedEvents.stream()
                .filter(e -> e instanceof com.gme.pay.txn.outbox.PaymentApprovedEvent)
                .map(e -> (com.gme.pay.txn.outbox.PaymentApprovedEvent) e)
                .findFirst().orElseThrow();
        assertEquals(700L, approved.partnerId());
        assertEquals("PARTNER-TXN-9", approved.partnerTxnRef());
        assertEquals(txn.txnRef(), approved.aggregateId());
        assertEquals("payment.approved", approved.eventType());
    }

    @Test
    @DisplayName("TransitionBlockedException message contains txnRef, from, and to")
    void exceptionMessageContainsContext() {
        Transaction txn = newTransaction();
        stateMachine.transition(txn, TransactionStatus.APPROVED);

        TransitionBlockedException ex = assertThrows(TransitionBlockedException.class,
                () -> stateMachine.transition(txn, TransactionStatus.CREATED));

        String msg = ex.getMessage();
        assertTrue(msg.contains(txn.txnRef()), "message should contain txnRef");
        assertTrue(msg.contains("APPROVED"),   "message should contain from-state");
        assertTrue(msg.contains("CREATED"),    "message should contain to-state");
    }
}
