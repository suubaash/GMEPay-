package com.gme.pay.txn.statemachine;

import com.gme.pay.contracts.events.TransactionCommittedPayload;
import com.gme.pay.events.DomainEvent;
import com.gme.pay.events.EventPublisher;
import com.gme.pay.txn.domain.model.Transaction;
import com.gme.pay.txn.domain.model.TransactionStatus;
import com.gme.pay.txn.domain.statemachine.TransactionStateMachine;
import com.gme.pay.txn.outbox.TransactionCommittedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the commit path emits {@link TransactionCommittedEvent} (topic
 * {@code gmepay.transaction.committed}) when a V003 txn reaches APPROVED, alongside the existing
 * status-changed + payment.approved events, and that the FX projection is captured first.
 */
class CommittedEventPublishTest {

    private final List<DomainEvent> published = new ArrayList<>();
    private TransactionStateMachine stateMachine;

    @BeforeEach
    void setUp() {
        published.clear();
        stateMachine = new TransactionStateMachine((EventPublisher) published::add);
    }

    private static Transaction committableTxn() {
        Transaction txn = new Transaction(
                700L, "PTX-1", "zeropay_kr", "OUTBOUND", "CPM",
                new BigDecimal("130000.00000000"), "KRW",
                new BigDecimal("11000000.00000000"), "IDR",
                "M-1", "Q-1");
        txn.applyStatusPatch("SCH-1", "AP-1", new BigDecimal("673.07690000"),
                Instant.now(), null, null, null);
        return txn;
    }

    @Test
    @DisplayName("APPROVED emits transaction.committed with the captured FX projection")
    void emitsCommittedEvent() {
        Transaction txn = committableTxn();
        stateMachine.transition(txn, TransactionStatus.PENDING_DEBIT);
        stateMachine.transition(txn, TransactionStatus.APPROVED);

        // FX captured on the aggregate at commit.
        assertNotNull(txn.committedAt());
        assertNotNull(txn.crossRate());

        TransactionCommittedEvent committed = published.stream()
                .filter(e -> e instanceof TransactionCommittedEvent)
                .map(TransactionCommittedEvent.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no transaction.committed event published"));

        assertEquals(TransactionCommittedPayload.EVENT_TYPE, committed.eventType());
        assertEquals(txn.txnRef(), committed.aggregateId());
        assertEquals(700L, committed.partnerId());
        assertEquals("KRW", committed.payoutCcy());
        assertEquals("IDR", committed.collectionCcy());
        assertFalse(committed.sameCcyShortcircuit());
        assertEquals(0, committed.crossRate().compareTo(txn.crossRate()));
    }

    @Test
    @DisplayName("legacy 5-field txn (null partnerId) emits no committed event")
    void noCommittedEventForLegacyTxn() {
        Transaction legacy = new Transaction("partner-1",
                new BigDecimal("100.00"), "USD", new BigDecimal("130000"), "KRW");
        stateMachine.transition(legacy, TransactionStatus.PENDING_DEBIT);
        stateMachine.transition(legacy, TransactionStatus.APPROVED);

        assertTrue(published.stream().noneMatch(e -> e instanceof TransactionCommittedEvent));
    }

    @Test
    @DisplayName("REFUNDED stamps refundedAt so the refund query can find it")
    void refundedStampsRefundedAt() {
        Transaction txn = committableTxn();
        stateMachine.transition(txn, TransactionStatus.PENDING_DEBIT);
        stateMachine.transition(txn, TransactionStatus.APPROVED);
        assertNull(txn.refundedAt());

        stateMachine.transition(txn, TransactionStatus.REFUNDED);
        assertNotNull(txn.refundedAt());
    }
}
