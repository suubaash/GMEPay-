package com.gme.pay.txn.service;

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
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ExpirySweeperService} (OI-01: 60-second approval timeout).
 *
 * <p>All tests use a fixed {@link Clock} so timing is fully deterministic.
 * No Spring context, no Docker, no H2 — the {@link StubTransactionRepository}
 * simulates persistence entirely in-memory.
 */
class ExpirySweeperServiceTest {

    /** Fixed "now" for all tests (2026-06-15T10:00:00Z). */
    private static final Instant NOW = Instant.parse("2026-06-15T10:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    /** Timeout used in these tests (default 60 s, matches production default). */
    private static final long TIMEOUT_SECONDS = 60L;

    private StubTransactionRepository repository;
    private TransactionStateMachine stateMachine;
    private ExpirySweeperService sweeper;
    private List<DomainEvent> publishedEvents;

    @BeforeEach
    void setUp() {
        publishedEvents = new ArrayList<>();
        EventPublisher capturingPublisher = publishedEvents::add;
        stateMachine = new TransactionStateMachine(capturingPublisher);
        repository = new StubTransactionRepository();
        sweeper = new ExpirySweeperService(
                repository, stateMachine, FIXED_CLOCK, TIMEOUT_SECONDS, true);
    }

    // ------------------------------------------------------------------
    // Helper: create a transaction with a specific createdAt (bypasses Instant.now())
    // ------------------------------------------------------------------

    private Transaction txnCreatedAt(Instant createdAt) {
        // Build via legacy 5-field ctor then rehydrate with the desired createdAt
        return new Transaction(
                "txn-" + createdAt.toEpochMilli(),
                "partner-1",
                new BigDecimal("100.00"), "USD",
                new BigDecimal("130000"), "KRW",
                TransactionStatus.CREATED,
                createdAt,
                createdAt,
                null, (java.math.RoundingMode) null, null);
    }

    private Transaction txnCreatedAtWithStatus(Instant createdAt, TransactionStatus status) {
        return new Transaction(
                "txn-" + status.name() + "-" + createdAt.toEpochMilli(),
                "partner-1",
                new BigDecimal("100.00"), "USD",
                new BigDecimal("130000"), "KRW",
                status,
                createdAt,
                createdAt,
                null, (java.math.RoundingMode) null, null);
    }

    // ------------------------------------------------------------------
    // Test: a just-created transaction is NOT swept
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A transaction created NOW (0s old) is NOT swept")
    void freshTransactionNotSwept() {
        Transaction fresh = txnCreatedAt(NOW); // created exactly at NOW
        repository.save(fresh);

        sweeper.sweep();

        Transaction after = repository.findByTxnRef(fresh.txnRef()).orElseThrow();
        assertEquals(TransactionStatus.CREATED, after.status(),
                "A just-created transaction must remain CREATED");
        assertNull(after.failureReason(), "No failure reason expected on fresh txn");
    }

    @Test
    @DisplayName("A transaction created 59 seconds ago is NOT swept (just within timeout)")
    void almostExpiredNotSwept() {
        Instant createdAt = NOW.minusSeconds(59); // 59s ago — still within 60s window
        Transaction txn = txnCreatedAt(createdAt);
        repository.save(txn);

        sweeper.sweep();

        Transaction after = repository.findByTxnRef(txn.txnRef()).orElseThrow();
        assertEquals(TransactionStatus.CREATED, after.status());
    }

    // ------------------------------------------------------------------
    // Test: a 61-second-old PENDING transaction IS swept to FAILED + reason + statusHistory
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A CREATED txn 61s old is swept to FAILED with APPROVAL_TIMEOUT reason")
    void expiredCreatedTransactionSweptToFailed() {
        Instant createdAt = NOW.minusSeconds(61); // 61s ago — past the 60s window
        Transaction txn = txnCreatedAt(createdAt);
        repository.save(txn);

        sweeper.sweep();

        Transaction after = repository.findByTxnRef(txn.txnRef()).orElseThrow();
        assertEquals(TransactionStatus.FAILED, after.status(),
                "Expired CREATED txn must be FAILED after sweep");
        assertEquals(ExpirySweeperService.REASON_APPROVAL_TIMEOUT, after.failureReason(),
                "Failure reason must be APPROVAL_TIMEOUT");
    }

    @Test
    @DisplayName("A PENDING_DEBIT txn 61s old is swept to FAILED with APPROVAL_TIMEOUT reason")
    void expiredPendingDebitTransactionSweptToFailed() {
        Instant createdAt = NOW.minusSeconds(61);
        Transaction txn = txnCreatedAtWithStatus(createdAt, TransactionStatus.PENDING_DEBIT);
        repository.save(txn);

        sweeper.sweep();

        Transaction after = repository.findByTxnRef(txn.txnRef()).orElseThrow();
        assertEquals(TransactionStatus.FAILED, after.status());
        assertEquals(ExpirySweeperService.REASON_APPROVAL_TIMEOUT, after.failureReason());
    }

    @Test
    @DisplayName("Sweeping a CREATED txn publishes exactly one TransactionStatusChanged event")
    void sweepPublishesDomainEvent() {
        Instant createdAt = NOW.minusSeconds(61);
        Transaction txn = txnCreatedAt(createdAt);
        repository.save(txn);

        sweeper.sweep();

        assertEquals(1, publishedEvents.size(), "Exactly one domain event must be published");
        assertEquals("TransactionStatusChanged", publishedEvents.get(0).eventType());
        assertEquals(txn.txnRef(), publishedEvents.get(0).aggregateId());
    }

    // ------------------------------------------------------------------
    // Test: APPROVED transaction is NEVER swept (FSM safety)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("APPROVED transaction (terminal) is never swept, even if very old")
    void approvedTransactionNeverSwept() {
        Instant veryOld = NOW.minusSeconds(3600); // 1 hour old
        Transaction txn = txnCreatedAtWithStatus(veryOld, TransactionStatus.APPROVED);
        repository.save(txn);

        sweeper.sweep();

        Transaction after = repository.findByTxnRef(txn.txnRef()).orElseThrow();
        assertEquals(TransactionStatus.APPROVED, after.status(),
                "APPROVED is terminal — the sweeper must never touch it");
        assertNull(after.failureReason());
        assertTrue(publishedEvents.isEmpty(), "No events should be published for a skipped txn");
    }

    @Test
    @DisplayName("FAILED transaction (terminal) is never swept again")
    void alreadyFailedTransactionNotSwept() {
        Instant veryOld = NOW.minusSeconds(3600);
        Transaction txn = txnCreatedAtWithStatus(veryOld, TransactionStatus.FAILED);
        repository.save(txn);

        sweeper.sweep();

        Transaction after = repository.findByTxnRef(txn.txnRef()).orElseThrow();
        assertEquals(TransactionStatus.FAILED, after.status());
        assertTrue(publishedEvents.isEmpty());
    }

    @Test
    @DisplayName("CANCELLED transaction (terminal) is never swept")
    void cancelledTransactionNotSwept() {
        Instant veryOld = NOW.minusSeconds(3600);
        Transaction txn = txnCreatedAtWithStatus(veryOld, TransactionStatus.CANCELLED);
        repository.save(txn);

        sweeper.sweep();

        Transaction after = repository.findByTxnRef(txn.txnRef()).orElseThrow();
        assertEquals(TransactionStatus.CANCELLED, after.status());
    }

    // ------------------------------------------------------------------
    // Test: timeout is configurable
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Configurable timeout: 30s sweeper expires a 31s-old txn")
    void configurableTimeout_30s_sweepsAt31s() {
        ExpirySweeperService short30sSweeper = new ExpirySweeperService(
                repository, stateMachine, FIXED_CLOCK, 30L, true);

        Instant createdAt = NOW.minusSeconds(31); // 31s old → past 30s timeout
        Transaction txn = txnCreatedAt(createdAt);
        repository.save(txn);

        short30sSweeper.sweep();

        Transaction after = repository.findByTxnRef(txn.txnRef()).orElseThrow();
        assertEquals(TransactionStatus.FAILED, after.status());
        assertEquals(ExpirySweeperService.REASON_APPROVAL_TIMEOUT, after.failureReason());
    }

    @Test
    @DisplayName("Configurable timeout: 30s sweeper does NOT expire a 29s-old txn")
    void configurableTimeout_30s_doesNotSweepAt29s() {
        ExpirySweeperService short30sSweeper = new ExpirySweeperService(
                repository, stateMachine, FIXED_CLOCK, 30L, true);

        Instant createdAt = NOW.minusSeconds(29); // 29s old → still within 30s window
        Transaction txn = txnCreatedAt(createdAt);
        repository.save(txn);

        short30sSweeper.sweep();

        Transaction after = repository.findByTxnRef(txn.txnRef()).orElseThrow();
        assertEquals(TransactionStatus.CREATED, after.status());
    }

    // ------------------------------------------------------------------
    // Test: sweeper disabled
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Sweeper disabled via config: expired transactions are not swept")
    void disabledSweeperDoesNothing() {
        ExpirySweeperService disabledSweeper = new ExpirySweeperService(
                repository, stateMachine, FIXED_CLOCK, TIMEOUT_SECONDS, false);

        Instant createdAt = NOW.minusSeconds(120); // 2 minutes old
        Transaction txn = txnCreatedAt(createdAt);
        repository.save(txn);

        disabledSweeper.sweep();

        Transaction after = repository.findByTxnRef(txn.txnRef()).orElseThrow();
        assertEquals(TransactionStatus.CREATED, after.status(),
                "Disabled sweeper must not change any status");
    }

    // ------------------------------------------------------------------
    // Test: mixed batch — only expired non-terminal ones are swept
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Batch: only expired non-terminal txns swept; fresh + terminal ones untouched")
    void batchSweepOnlyTargetsEligibleTransactions() {
        Transaction fresh   = txnCreatedAt(NOW.minusSeconds(5));           // too new
        Transaction expired = txnCreatedAt(NOW.minusSeconds(61));          // sweep target
        Transaction approved = txnCreatedAtWithStatus(NOW.minusSeconds(120), TransactionStatus.APPROVED);

        repository.save(fresh);
        repository.save(expired);
        repository.save(approved);

        sweeper.sweep();

        assertEquals(TransactionStatus.CREATED,  repository.findByTxnRef(fresh.txnRef()).orElseThrow().status());
        assertEquals(TransactionStatus.FAILED,   repository.findByTxnRef(expired.txnRef()).orElseThrow().status());
        assertEquals(TransactionStatus.APPROVED, repository.findByTxnRef(approved.txnRef()).orElseThrow().status());

        // Only one event for the one swept txn
        assertEquals(1, publishedEvents.size());
    }

    // ------------------------------------------------------------------
    // Stub in-memory repository — no H2, no Spring context needed
    // ------------------------------------------------------------------

    /**
     * Minimal in-memory stub that implements the sweepable subset of
     * {@link TransactionRepository}.
     *
     * <p>{@link #findExpiredNonTerminal} applies the same logic as
     * {@link InMemoryTransactionRepository}: only CREATED and PENDING_DEBIT
     * rows older than the cutoff are returned.
     */
    private static class StubTransactionRepository implements TransactionRepository {

        private static final java.util.Set<TransactionStatus> SWEEPABLE = java.util.Set.of(
                TransactionStatus.CREATED, TransactionStatus.PENDING_DEBIT);

        private final Map<String, Transaction> store = new ConcurrentHashMap<>();

        @Override
        public Transaction save(Transaction txn) {
            store.put(txn.txnRef(), txn);
            return txn;
        }

        @Override
        public Optional<Transaction> findByTxnRef(String txnRef) {
            return Optional.ofNullable(store.get(txnRef));
        }

        @Override
        public List<Transaction> findExpiredNonTerminal(Instant expiryBefore) {
            return store.values().stream()
                    .filter(t -> SWEEPABLE.contains(t.status()))
                    .filter(t -> t.createdAt().isBefore(expiryBefore))
                    .collect(Collectors.toList());
        }

        @Override
        public Page<Transaction> findByFilters(LocalDate from, LocalDate to,
                                               TransactionStatus status, Long partnerId,
                                               String txnRef, String schemeTxnRef, String merchantId,
                                               Pageable pageable) {
            throw new UnsupportedOperationException("not needed in sweeper tests");
        }

        @Override
        public List<Transaction> findStuck(Instant stuckBefore, List<String> sweepStatuses) {
            throw new UnsupportedOperationException("not needed in sweeper tests");
        }

        @Override
        public List<Transaction> findCommittedFx(LocalDate from, LocalDate to, Long partnerId) {
            throw new UnsupportedOperationException("not needed in sweeper tests");
        }

        @Override
        public List<Transaction> findRefundedOn(LocalDate refundedOn) {
            throw new UnsupportedOperationException("not needed in sweeper tests");
        }
    }
}
