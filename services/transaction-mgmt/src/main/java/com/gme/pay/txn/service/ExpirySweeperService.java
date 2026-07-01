package com.gme.pay.txn.service;

import com.gme.pay.txn.domain.model.Transaction;
import com.gme.pay.txn.domain.model.TransactionStatus;
import com.gme.pay.txn.domain.statemachine.TransactionStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Scheduled sweeper for OI-01: 60-second approval timeout.
 *
 * <p>Every {@code ~10 seconds} (fixed-rate, KST time zone) this component queries
 * for transactions in a non-terminal initial state (CREATED or PENDING_DEBIT) whose
 * {@code createdAt} is older than {@code gmepay.txn.approval-timeout-seconds} seconds
 * and transitions them to {@link TransactionStatus#FAILED} with reason
 * {@code APPROVAL_TIMEOUT}.
 *
 * <p>FSM safety: only CREATED and PENDING_DEBIT are swept — these are the only
 * non-terminal states that can legally reach FAILED per
 * {@link com.gme.pay.txn.domain.statemachine.TransactionTransitions}.
 * APPROVED, FAILED, and CANCELLED are terminal and are never touched by this sweeper.
 *
 * <p>The sweeper is gated on {@code gmepay.txn.expiry-sweeper.enabled} (default {@code true}).
 * It is entirely exception-safe: any error on an individual transaction is logged and skipped
 * so a single bad row cannot abort the entire sweep batch.
 */
@Component
public class ExpirySweeperService {

    private static final Logger log = LoggerFactory.getLogger(ExpirySweeperService.class);

    /** Reason code recorded in the transaction aggregate on TTL expiry. */
    public static final String REASON_APPROVAL_TIMEOUT = "APPROVAL_TIMEOUT";

    private final TransactionRepository repository;
    private final TransactionStateMachine stateMachine;
    private final Clock clock;
    private final long approvalTimeoutSeconds;
    private final boolean enabled;

    /**
     * Primary constructor (used by Spring for the real application).
     *
     * <p>Uses the system UTC clock. Tests inject their own {@link Clock} via
     * {@link #ExpirySweeperService(TransactionRepository, TransactionStateMachine, Clock, long, boolean)}.
     *
     * @param approvalTimeoutSeconds configurable via {@code gmepay.txn.approval-timeout-seconds}
     * @param enabled                configurable via {@code gmepay.txn.expiry-sweeper.enabled}
     */
    @Autowired
    public ExpirySweeperService(
            TransactionRepository repository,
            TransactionStateMachine stateMachine,
            @Value("${gmepay.txn.approval-timeout-seconds:60}") long approvalTimeoutSeconds,
            @Value("${gmepay.txn.expiry-sweeper.enabled:true}") boolean enabled) {
        this(repository, stateMachine, Clock.systemUTC(), approvalTimeoutSeconds, enabled);
    }

    /**
     * Clock-injectable constructor used by unit tests for deterministic time control.
     */
    public ExpirySweeperService(
            TransactionRepository repository,
            TransactionStateMachine stateMachine,
            Clock clock,
            long approvalTimeoutSeconds,
            boolean enabled) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.stateMachine = Objects.requireNonNull(stateMachine, "stateMachine");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.approvalTimeoutSeconds = approvalTimeoutSeconds;
        this.enabled = enabled;
    }

    /**
     * Scheduled sweep — runs approximately every 10 seconds.
     *
     * <p>Each expired transaction is transitioned to FAILED in its own
     * {@code @Transactional} method ({@link #expireOne}) so a single failure
     * does not roll back the entire batch.
     */
    @Scheduled(fixedDelayString = "${gmepay.txn.expiry-sweeper.interval-ms:10000}",
               zone = "Asia/Seoul")
    @SchedulerLock(name = "ExpirySweeperService_sweep",
                   lockAtMostFor = "PT1M", lockAtLeastFor = "PT2S")
    public void sweep() {
        if (!enabled) {
            return;
        }

        Instant expiryBefore = clock.instant().minusSeconds(approvalTimeoutSeconds);
        List<Transaction> expired;
        try {
            expired = repository.findExpiredNonTerminal(expiryBefore);
        } catch (Exception ex) {
            log.error("[ExpirySweeperService] Failed to query expired transactions", ex);
            return;
        }

        if (expired.isEmpty()) {
            return;
        }

        log.info("[ExpirySweeperService] Found {} expired non-terminal transaction(s), sweeping to FAILED",
                expired.size());

        for (Transaction txn : expired) {
            try {
                expireOne(txn.txnRef());
            } catch (Exception ex) {
                log.error("[ExpirySweeperService] Failed to expire txn {} — skipping",
                        txn.txnRef(), ex);
            }
        }
    }

    /**
     * Transitions a single transaction to FAILED with reason APPROVAL_TIMEOUT.
     * Runs in its own transaction so failures are isolated.
     */
    @Transactional
    public void expireOne(String txnRef) {
        Transaction txn = repository.findByTxnRef(txnRef)
                .orElse(null);

        if (txn == null) {
            // Already deleted — harmless
            log.debug("[ExpirySweeperService] txn {} not found — already gone", txnRef);
            return;
        }

        // Double-check: another thread/node may have already transitioned it
        if (txn.status().isTerminal()) {
            log.debug("[ExpirySweeperService] txn {} is already terminal ({}), skipping",
                    txnRef, txn.status());
            return;
        }

        TransactionStatus fromStatus = txn.status();
        stateMachine.transition(txn, TransactionStatus.FAILED);
        txn.applyFailureReason(REASON_APPROVAL_TIMEOUT);
        repository.save(txn);

        log.info("[ExpirySweeperService] Expired txn {} ({} -> FAILED, reason={})",
                txnRef, fromStatus, REASON_APPROVAL_TIMEOUT);
    }
}
