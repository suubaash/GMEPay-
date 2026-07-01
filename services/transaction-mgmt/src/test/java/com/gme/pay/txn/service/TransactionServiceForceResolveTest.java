package com.gme.pay.txn.service;

import com.gme.pay.errors.ApiException;
import com.gme.pay.events.DomainEvent;
import com.gme.pay.events.EventPublisher;
import com.gme.pay.txn.domain.model.Transaction;
import com.gme.pay.txn.domain.model.TransactionStatus;
import com.gme.pay.txn.domain.statemachine.TransactionStateMachine;
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
 * Unit tests for {@link TransactionService#resolveByOperator} — the Ops force-resolve of an
 * UNCERTAIN transaction to a terminal state (COMPLETED→APPROVED / REVERSED), recording the
 * reason + operator in the aggregate audit. In-memory fake repo, real state machine.
 */
class TransactionServiceForceResolveTest {

    private final List<DomainEvent> publishedEvents = new ArrayList<>();
    private FakeRepo repo;
    private TransactionService service;

    @BeforeEach
    void setUp() {
        publishedEvents.clear();
        repo = new FakeRepo();
        EventPublisher capturing = publishedEvents::add;
        service = new TransactionService(repo, new TransactionStateMachine(capturing));
    }

    private String seedUncertain() {
        Transaction txn = new Transaction(
                700L, "PARTNER-TXN-1", "zeropay", "INBOUND", "MPM",
                new BigDecimal("45000"), "KRW", new BigDecimal("33.88"), "USD", "M-1", "Q-1");
        repo.save(txn);
        String ref = txn.txnRef();
        service.toPendingDebit(ref);
        service.toSchemeSent(ref);
        service.toUncertain(ref);
        return ref;
    }

    @Test
    @DisplayName("resolveByOperator(REVERSED) moves UNCERTAIN→REVERSED and records reason + operator")
    void resolveReversedRecordsAudit() {
        String ref = seedUncertain();
        Transaction resolved = service.resolveByOperator(ref, "REVERSED", "scheme confirmed no-pay", "op.kai");

        assertEquals(TransactionStatus.REVERSED, resolved.status());
        assertEquals("scheme confirmed no-pay", resolved.resolutionReason());
        assertEquals("op.kai", resolved.resolvedBy());
        assertNotNull(resolved.resolvedAt(), "resolvedAt must be stamped");
    }

    @Test
    @DisplayName("resolveByOperator(COMPLETED) moves UNCERTAIN→APPROVED")
    void resolveCompletedApproves() {
        String ref = seedUncertain();
        Transaction resolved = service.resolveByOperator(ref, "COMPLETED", "manual recon", "op.kai");
        assertEquals(TransactionStatus.APPROVED, resolved.status());
    }

    @Test
    @DisplayName("resolveByOperator is idempotent: a repeat once REVERSED is a no-op")
    void resolveIsIdempotent() {
        String ref = seedUncertain();
        service.resolveByOperator(ref, "REVERSED", "reason", "op.kai");
        int eventsAfterFirst = publishedEvents.size();

        Transaction again = service.resolveByOperator(ref, "REVERSED", "reason", "op.kai");
        assertEquals(TransactionStatus.REVERSED, again.status());
        assertEquals(eventsAfterFirst, publishedEvents.size(),
                "idempotent repeat must not re-transition or publish another event");
    }

    @Test
    @DisplayName("resolveByOperator rejects a transaction that is not UNCERTAIN")
    void rejectsNonUncertain() {
        // Fresh CREATED transaction — never reached UNCERTAIN.
        Transaction txn = new Transaction(
                700L, "PARTNER-TXN-2", "zeropay", "INBOUND", "MPM",
                new BigDecimal("45000"), "KRW", new BigDecimal("33.88"), "USD", "M-1", "Q-1");
        repo.save(txn);
        assertThrows(ApiException.class,
                () -> service.resolveByOperator(txn.txnRef(), "REVERSED", "reason", "op.kai"));
    }

    @Test
    @DisplayName("resolveByOperator rejects a bad resolution / blank reason / blank operator")
    void rejectsBadInput() {
        String ref = seedUncertain();
        assertThrows(ApiException.class,
                () -> service.resolveByOperator(ref, "BOGUS", "reason", "op.kai"));
        assertThrows(ApiException.class,
                () -> service.resolveByOperator(ref, "REVERSED", "  ", "op.kai"));
        assertThrows(ApiException.class,
                () -> service.resolveByOperator(ref, "REVERSED", "reason", null));
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
