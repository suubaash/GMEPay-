package com.gme.pay.txn.service;

import com.gme.pay.contracts.events.OpsAlertPayload;
import com.gme.pay.events.DomainEvent;
import com.gme.pay.events.EventPublisher;
import com.gme.pay.txn.domain.model.Transaction;
import com.gme.pay.txn.domain.model.TransactionStatus;
import com.gme.pay.txn.domain.statemachine.TransactionStateMachine;
import com.gme.pay.txn.outbox.OpsAlertEvent;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link StuckTransactionAlertSweeper} — the Ops stuck/aged-transaction alert sweep.
 * A capturing {@link EventPublisher} records emitted {@link OpsAlertEvent}s; a fixed {@link Clock}
 * makes an UNCERTAIN txn appear aged past the threshold.
 */
class StuckTransactionAlertSweeperTest {

    private static final long THRESHOLD = 900;   // 15 min
    private static final long CRITICAL_X = 4;

    /** Drives a fresh transaction up to UNCERTAIN and returns it. */
    private Transaction seedUncertain(TransactionService service, FakeRepo repo) {
        Transaction txn = new Transaction(
                700L, "PARTNER-TXN-1", "zeropay", "INBOUND", "MPM",
                new BigDecimal("45000"), "KRW", new BigDecimal("33.88"), "USD", "M-1", "Q-1");
        repo.save(txn);
        service.toPendingDebit(txn.txnRef());
        service.toSchemeSent(txn.txnRef());
        service.toUncertain(txn.txnRef());
        return repo.findByTxnRef(txn.txnRef()).orElseThrow();
    }

    @Test
    @DisplayName("sweep emits an UNCERTAIN_AGED ops.alert for an aged UNCERTAIN txn")
    void emitsAlertForAgedUncertain() {
        List<DomainEvent> published = new ArrayList<>();
        FakeRepo repo = new FakeRepo();
        TransactionService service = new TransactionService(repo, new TransactionStateMachine(published::add));
        Transaction stuck = seedUncertain(service, repo);
        repo.stuck.add(stuck);

        // Clock 30 minutes after the txn's last update — well past the 15-min WARN threshold,
        // but under the 60-min CRITICAL escalation.
        Instant now = stuck.updatedAt().plusSeconds(THRESHOLD * 2);
        StuckTransactionAlertSweeper sweeper = new StuckTransactionAlertSweeper(
                repo, published::add, Clock.fixed(now, ZoneOffset.UTC),
                true, THRESHOLD, CRITICAL_X, List.of(TransactionStatus.UNCERTAIN.name()));

        List<OpsAlertEvent> alerts = sweeper.emitAlerts();

        assertEquals(1, alerts.size());
        OpsAlertEvent alert = alerts.get(0);
        assertEquals(StuckTransactionAlertSweeper.ALERT_UNCERTAIN_AGED, alert.alertType());
        assertEquals(StuckTransactionAlertSweeper.SEVERITY_WARN, alert.severity());
        assertEquals(stuck.txnRef(), alert.subjectRef());

        // The canonical payload carries eventType ops.alert (drives topic gmepay.ops.alert).
        OpsAlertPayload payload = alert.toPayload();
        assertEquals(OpsAlertPayload.EVENT_TYPE, payload.eventType());
        assertEquals(stuck.txnRef(), payload.subjectRef());

        // Published through the EventPublisher seam.
        assertTrue(published.stream().anyMatch(e -> OpsAlertPayload.EVENT_TYPE.equals(e.eventType())),
                "an ops.alert event must be published via the EventPublisher seam");
    }

    @Test
    @DisplayName("sweep escalates to CRITICAL once aged past the critical multiplier")
    void escalatesToCritical() {
        List<DomainEvent> published = new ArrayList<>();
        FakeRepo repo = new FakeRepo();
        TransactionService service = new TransactionService(repo, new TransactionStateMachine(published::add));
        Transaction stuck = seedUncertain(service, repo);
        repo.stuck.add(stuck);

        Instant now = stuck.updatedAt().plusSeconds(THRESHOLD * CRITICAL_X + 1);
        StuckTransactionAlertSweeper sweeper = new StuckTransactionAlertSweeper(
                repo, published::add, Clock.fixed(now, ZoneOffset.UTC),
                true, THRESHOLD, CRITICAL_X, List.of(TransactionStatus.UNCERTAIN.name()));

        List<OpsAlertEvent> alerts = sweeper.emitAlerts();
        assertEquals(1, alerts.size());
        assertEquals(StuckTransactionAlertSweeper.SEVERITY_CRITICAL, alerts.get(0).severity());
    }

    @Test
    @DisplayName("disabled sweep is a no-op (nothing published)")
    void disabledSweepIsNoop() {
        List<DomainEvent> published = new ArrayList<>();
        FakeRepo repo = new FakeRepo();
        TransactionService service = new TransactionService(repo, new TransactionStateMachine(published::add));
        Transaction stuck = seedUncertain(service, repo);
        repo.stuck.add(stuck);
        int before = published.size();

        StuckTransactionAlertSweeper sweeper = new StuckTransactionAlertSweeper(
                repo, published::add, Clock.systemUTC(),
                false, THRESHOLD, CRITICAL_X, List.of(TransactionStatus.UNCERTAIN.name()));
        sweeper.sweep();

        assertEquals(before, published.size(), "disabled sweeper must publish nothing");
    }

    @Test
    @DisplayName("sweep() carries @SchedulerLock so replicas do not double-fire (#3)")
    void sweepMethodIsSchedulerLocked() throws NoSuchMethodException {
        Method sweep = StuckTransactionAlertSweeper.class.getMethod("sweep");
        SchedulerLock lock = sweep.getAnnotation(SchedulerLock.class);
        assertNotNull(lock, "sweep() must be annotated @SchedulerLock for distributed locking");
        assertFalse(lock.name().isBlank(), "@SchedulerLock must carry a stable lock name");
    }

    private static final class FakeRepo implements TransactionRepository {
        private final Map<String, Transaction> store = new HashMap<>();
        final List<Transaction> stuck = new ArrayList<>();

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
        @Override public List<Transaction> findStuck(Instant stuckBefore, List<String> sweepStatuses) {
            return List.copyOf(stuck);
        }
        @Override public List<Transaction> findCommittedFx(LocalDate from, LocalDate to, Long partnerId) { return List.of(); }
        @Override public List<Transaction> findRefundedOn(LocalDate refundedOn) { return List.of(); }
    }
}
