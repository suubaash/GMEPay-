package com.gme.pay.payment.opsrun;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Writes one {@code ledger_ops_runs} row per ledger-ops run (gap <b>T2-5</b>) — payment-executor's instance
 * of the durability pattern settlement-reconciliation's {@code BatchRunRecorder} established for T3-4, not a
 * second mechanism. The pattern is reused verbatim; only the table is local, because {@code batch_runs} lives
 * in settlement's own database and reaching across services' schemas is forbidden by
 * {@code INTER_SERVICE_CONTRACTS.md}.
 *
 * <h2>Why {@code REQUIRES_NEW} is load-bearing, not decoration</h2>
 * <p>The work these jobs wrap is {@code @Transactional} (the replay sweep updates
 * {@code revenue_posting_failures}; the day-close upserts {@code day_close_reports}). When that work throws,
 * Spring marks the transaction rollback-only and unwinds it — which is correct (no half-written report) and is
 * exactly why nothing survived to say the run happened. A write on the SAME transaction would be rolled back
 * with it; a write attempted afterwards that joined a still-rollback-marked transaction would fail with
 * {@code UnexpectedRollbackException}.
 *
 * <p>{@link Propagation#REQUIRES_NEW} suspends whatever transaction is in flight and commits this row on its
 * own connection, so the evidence lands whether the recorder is called from a catch block outside the failed
 * transaction or from inside a still-unwinding one.
 *
 * <h2>Never throws</h2>
 * <p>Every method swallows its own failure into a log line and returns {@code null}. A recorder that threw
 * would replace the original exception with a bookkeeping error in the caller's catch block — losing the
 * actual diagnosis to the machinery meant to preserve it.
 */
@Component
public class LedgerOpsRunRecorder {

    /** Stack excerpt cap — matches {@code failure_trace VARCHAR(4000)}. */
    private static final int TRACE_LIMIT = 4000;

    /** {@code alert_status} values. An alert was raised onto the T3-3 pipeline. */
    public static final String ALERT_EMITTED = "EMITTED";
    /** The alert could not even be handed to the pipeline (which itself never throws, so this is rare). */
    public static final String ALERT_EMIT_FAILED = "EMIT_FAILED";
    /** Nothing to alert about (a successful or deliberately-skipped run). */
    public static final String ALERT_NOT_APPLICABLE = "NOT_APPLICABLE";

    private static final Logger log = LoggerFactory.getLogger(LedgerOpsRunRecorder.class);

    private final LedgerOpsRunRepository repository;

    public LedgerOpsRunRecorder(LedgerOpsRunRepository repository) {
        this.repository = repository;
    }

    /**
     * One run's identifying coordinates.
     *
     * @param job          one of the {@link LedgerOpsJob} constants
     * @param businessDate the date the run was FOR, or null for a non-date-scoped job
     * @param trigger      scheduler or operator
     * @param operatorId   who asked, for an operator-triggered run; null otherwise
     */
    public record RunKey(String job, @Nullable LocalDate businessDate, LedgerOpsRunTrigger trigger,
                         @Nullable String operatorId) {

        /** Scheduled run: no operator attribution (the cron is the actor). */
        public static RunKey scheduled(String job, @Nullable LocalDate businessDate) {
            return new RunKey(job, businessDate, LedgerOpsRunTrigger.SCHEDULER, null);
        }

        /** Operator-triggered run, attributed. */
        public static RunKey operator(String job, @Nullable LocalDate businessDate,
                                      @Nullable String operatorId) {
            return new RunKey(job, businessDate, LedgerOpsRunTrigger.OPERATOR, operatorId);
        }
    }

    /**
     * Record a SUCCESSFUL run, so "did last night's replay happen?" is answerable from one table.
     *
     * @return the persisted row id, or {@code null} if even this write failed (already logged)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long recordSuccess(RunKey key, Instant startedAt, String summary,
                              @Nullable Integer recordCount) {
        LedgerOpsRunEntity row = base(key, LedgerOpsRunOutcome.SUCCESS, startedAt);
        row.setSummary(summary);
        row.setRecordCount(recordCount);
        row.setAlertStatus(ALERT_NOT_APPLICABLE);
        return save(row, key);
    }

    /** Record a FAILED run. Committed in its own transaction so it survives the caller's rollback. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long recordFailure(RunKey key, Instant startedAt, @Nullable Throwable cause) {
        LedgerOpsRunEntity row = base(key, LedgerOpsRunOutcome.FAILED, startedAt);
        if (cause != null) {
            row.setFailureClass(cause.getClass().getName());
            row.setFailureMessage(cause.getMessage() == null
                    ? cause.getClass().getSimpleName() + " (no message)" : cause.getMessage());
            row.setFailureTrace(stackExcerpt(cause));
        } else {
            row.setFailureClass("UnknownFailure");
            row.setFailureMessage("run reported failure with no exception");
        }
        return save(row, key);
    }

    /** Record a run a feature gate deliberately did not perform. Not an incident, not alerted. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long recordSkipped(RunKey key, Instant startedAt, String why) {
        LedgerOpsRunEntity row = base(key, LedgerOpsRunOutcome.SKIPPED_DISABLED, startedAt);
        row.setSummary(why);
        row.setAlertStatus(ALERT_NOT_APPLICABLE);
        return save(row, key);
    }

    /**
     * Stamp the alert outcome onto an already-written run row, so one row answers both "what failed?" and
     * "was an alert raised?".
     *
     * <p>Note the honest limit of this column: {@code OpsAlertPipeline} owns the real delivery outcome and
     * stamps it onto the {@code ops_alerts} row (T3-3). This column records only whether payment-executor
     * handed the alert to that pipeline — the two together are the full chain.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAlertOutcome(@Nullable Long runId, String alertStatus, @Nullable String alertError) {
        if (runId == null || alertStatus == null) {
            return;
        }
        try {
            repository.findById(runId).ifPresent(row -> {
                row.setAlertStatus(alertStatus);
                row.setAlertError(alertError);
                repository.save(row);
            });
        } catch (Exception e) {
            log.error("ledger_ops_runs: could not stamp alert outcome {} on run {}: {}",
                    alertStatus, runId, e.getMessage(), e);
        }
    }

    private LedgerOpsRunEntity base(RunKey key, LedgerOpsRunOutcome outcome, Instant startedAt) {
        LedgerOpsRunEntity row = new LedgerOpsRunEntity();
        row.setJob(key.job());
        row.setBusinessDate(key.businessDate());
        row.setOutcome(outcome.name());
        row.setTriggerSource(key.trigger().name());
        row.setOperatorId(key.operatorId());
        row.setStartedAt(startedAt == null ? Instant.now() : startedAt);
        row.setFinishedAt(Instant.now());
        return row;
    }

    private Long save(LedgerOpsRunEntity row, RunKey key) {
        try {
            return repository.save(row).getId();
        } catch (Exception e) {
            log.error("ledger_ops_runs: FAILED to persist the run ledger row for job={} date={} "
                            + "(outcome={}) — the run outcome is now only in this log line: {}",
                    key.job(), key.businessDate(), row.getOutcome(), e.getMessage(), e);
            return null;
        }
    }

    /**
     * A bounded stack excerpt: the message chain plus the head of the stack. Deliberately truncated rather
     * than stored whole — 4000 characters identify the throw site, and an unbounded blob per failure turns
     * the ledger into the thing that fills the disk.
     */
    static String stackExcerpt(Throwable cause) {
        StringWriter sw = new StringWriter();
        try (PrintWriter pw = new PrintWriter(sw)) {
            cause.printStackTrace(pw);
        }
        String full = sw.toString();
        return full.length() <= TRACE_LIMIT ? full : full.substring(0, TRACE_LIMIT);
    }
}
