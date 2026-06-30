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
 * Plain JUnit 5 tests for {@link TransactionService#resolveUncertain} — the batch-reconciliation
 * resolution of an UNCERTAIN transaction (P1 gap: UNCERTAIN now performs a real FSM transition).
 *
 * <p>Uses an in-memory fake {@link TransactionRepository} and a real {@link TransactionStateMachine}
 * with a capturing publisher — no Spring context, no DB.
 */
class TransactionServiceResolveUncertainTest {

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

    /** Drives a fresh transaction up to UNCERTAIN and returns its txnRef. */
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
    @DisplayName("resolveUncertain(APPROVED) on an UNCERTAIN txn transitions to APPROVED")
    void resolveApproved() {
        String ref = seedUncertain();
        Transaction resolved = service.resolveUncertain(ref, TransactionStatus.APPROVED);
        assertEquals(TransactionStatus.APPROVED, resolved.status());
    }

    @Test
    @DisplayName("resolveUncertain(FAILED) on an UNCERTAIN txn transitions to FAILED")
    void resolveFailed() {
        String ref = seedUncertain();
        Transaction resolved = service.resolveUncertain(ref, TransactionStatus.FAILED);
        assertEquals(TransactionStatus.FAILED, resolved.status());
    }

    @Test
    @DisplayName("resolveUncertain is idempotent: a second call after APPROVED is a no-op")
    void resolveIsIdempotent() {
        String ref = seedUncertain();
        service.resolveUncertain(ref, TransactionStatus.APPROVED);
        int eventsAfterFirst = publishedEvents.size();

        // Second call must NOT re-transition (APPROVED is terminal; a self-edge would throw).
        Transaction again = service.resolveUncertain(ref, TransactionStatus.APPROVED);
        assertEquals(TransactionStatus.APPROVED, again.status());
        assertEquals(eventsAfterFirst, publishedEvents.size(),
                "no further events should be published on the idempotent second call");
    }

    @Test
    @DisplayName("resolveUncertain rejects a non-terminal outcome")
    void resolveRejectsBadOutcome() {
        String ref = seedUncertain();
        assertThrows(ApiException.class,
                () -> service.resolveUncertain(ref, TransactionStatus.SCHEME_SENT));
    }

    // ------------------------------------------------------------------
    // In-memory fake repository
    // ------------------------------------------------------------------

    private static final class FakeRepo implements TransactionRepository {
        private final Map<String, Transaction> store = new HashMap<>();

        @Override public Transaction save(Transaction txn) {
            store.put(txn.txnRef(), txn);
            return txn;
        }

        @Override public Optional<Transaction> findByTxnRef(String txnRef) {
            return Optional.ofNullable(store.get(txnRef));
        }

        @Override public Page<Transaction> findByFilters(LocalDate from, LocalDate to,
                                                         TransactionStatus status, Long partnerId,
                                                         Pageable pageable) {
            return Page.empty(pageable);
        }

        @Override public List<Transaction> findExpiredNonTerminal(Instant expiryBefore) {
            return List.of();
        }
    }
}
