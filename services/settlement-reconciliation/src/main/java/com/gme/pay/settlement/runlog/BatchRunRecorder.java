package com.gme.pay.settlement.runlog;

import com.gme.pay.settlement.calendar.BusinessDayVerdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Writes one {@code batch_runs} row per batch-window run — the fix for the core half of gap <b>T3-4</b>:
 * <em>"a failed run rolls the whole transaction back and no ERROR row is persisted, so ops has no
 * visibility that a run failed at all."</em>
 *
 * <h2>Why {@code REQUIRES_NEW} is load-bearing, not decoration</h2>
 * <p>{@link com.gme.pay.settlement.batch.SettlementBatchJobService#runWindow} is {@code @Transactional}.
 * When it throws, Spring marks that transaction rollback-only and unwinds it — the batch row, the
 * settlement lines and the outbox event all vanish, which is correct (no half-written settlement) but is
 * exactly why nothing survived to say the run happened. A write on the SAME transaction would be rolled
 * back with it; even a write attempted after the rollback, if it joined a still-rollback-marked transaction,
 * would fail with {@code UnexpectedRollbackException}.
 *
 * <p>{@link Propagation#REQUIRES_NEW} suspends whatever transaction is in flight and commits this row on its
 * own connection, so the evidence lands whether the recorder is called from the scheduler's catch block
 * (outside the failed transaction) or from inside a still-unwinding one. That makes the ledger correct
 * under both call shapes rather than only the one currently used.
 *
 * <h2>Never throws</h2>
 * <p>Every method swallows its own failure into a log line. A recorder that threw would replace the
 * original exception with a bookkeeping error in the scheduler's catch block — losing the actual diagnosis
 * to the machinery meant to preserve it. When the ledger write itself fails, {@code record*} returns
 * {@code null} and the alert is still raised with {@code batch_runs.id=UNWRITTEN}.
 */
@Component
public class BatchRunRecorder {

    /** Outbound file generation (ZP0061/ZP0063/ZP0065/ZP0066). */
    public static final String KIND_GENERATION = "GENERATION";
    /** Inbound result-file reconciliation (ZP0062/ZP0064). */
    public static final String KIND_RECON = "RECON";

    /** Stack excerpt cap — matches {@code failure_trace VARCHAR(4000)}. */
    private static final int TRACE_LIMIT = 4000;

    private static final Logger log = LoggerFactory.getLogger(BatchRunRecorder.class);

    private final BatchRunRepository repository;

    public BatchRunRecorder(BatchRunRepository repository) {
        this.repository = repository;
    }

    /**
     * One run's identifying coordinates plus the calendar verdict in force for it. Passed as a value object
     * so the six-argument call sites in the schedulers stay readable.
     */
    public record RunKey(String runKind, String fileType, String settlementWindow, LocalDate businessDate,
                         BatchRunTrigger trigger, BusinessDayVerdict verdict,
                         String operatorId, String reason) {

        /** Scheduled run: no operator attribution (the cron is the actor). */
        public RunKey(String runKind, String fileType, String settlementWindow, LocalDate businessDate,
                      BatchRunTrigger trigger, BusinessDayVerdict verdict) {
            this(runKind, fileType, settlementWindow, businessDate, trigger, verdict, null, null);
        }

        public static RunKey generation(String fileType, String window, LocalDate date,
                                        BatchRunTrigger trigger, BusinessDayVerdict verdict) {
            return new RunKey(KIND_GENERATION, fileType, window, date, trigger, verdict);
        }

        public static RunKey recon(String fileType, String window, LocalDate date,
                                   BatchRunTrigger trigger, BusinessDayVerdict verdict) {
            return new RunKey(KIND_RECON, fileType, window, date, trigger, verdict);
        }
    }

    /**
     * Record a FAILED run. Committed in its own transaction so it survives the caller's rollback.
     *
     * @return the persisted row id, or {@code null} if even this write failed (already logged)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long recordFailure(RunKey key, Instant startedAt, Throwable cause) {
        BatchRunEntity row = base(key, BatchRunOutcome.FAILED, startedAt);
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

    /** Record a SUCCESSFUL run, so "did last night's 05:00 happen?" is answerable from one table. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long recordSuccess(RunKey key, Instant startedAt, String batchId, String batchStatus,
                              Integer recordCount) {
        BatchRunEntity row = base(key, BatchRunOutcome.SUCCESS, startedAt);
        row.setBatchId(batchId);
        row.setBatchStatus(batchStatus);
        row.setRecordCount(recordCount);
        return save(row, key);
    }

    /** Record a run the calendar or a feature gate deliberately did not perform. Not an incident. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long recordSkipped(RunKey key, Instant startedAt, BatchRunOutcome outcome, String why) {
        BatchRunEntity row = base(key, outcome, startedAt);
        row.setFailureMessage(why);   // reused as the "why skipped" note; outcome says it is not a failure
        row.setAlertStatus(BatchRunAlerter.AlertOutcome.NOT_APPLICABLE);
        return save(row, key);
    }

    /**
     * Stamp the notification outcome onto an already-written run row, so one row answers both "what
     * failed?" and "did anyone find out?" — the same contract payment-executor's {@code OpsAlertPipeline}
     * established for T3-3.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAlertOutcome(Long runId, BatchRunAlerter.AlertOutcome outcome) {
        if (runId == null || outcome == null) {
            return;
        }
        try {
            repository.findById(runId).ifPresent(row -> {
                row.setAlertStatus(outcome.status());
                row.setAlertError(outcome.error());
                repository.save(row);
            });
        } catch (Exception e) {
            log.error("batch_runs: could not stamp alert outcome {} on run {}: {}",
                    outcome.status(), runId, e.getMessage(), e);
        }
    }

    private BatchRunEntity base(RunKey key, BatchRunOutcome outcome, Instant startedAt) {
        BatchRunEntity row = new BatchRunEntity();
        row.setRunKind(key.runKind());
        row.setFileType(key.fileType());
        row.setSettlementWindow(key.settlementWindow() == null ? "-" : key.settlementWindow());
        row.setBusinessDate(key.businessDate());
        row.setOutcome(outcome.name());
        row.setTriggerSource(key.trigger().name());
        row.setCalendarVerdict((key.verdict() == null ? BusinessDayVerdict.UNVERIFIED : key.verdict()).name());
        // Operator attribution, set only for OPERATOR_RERUN — so the ledger answers "who regenerated
        // yesterday's ZP0061, and why?" without a separate audit lookup.
        row.setOperatorId(key.operatorId());
        row.setReason(key.reason());
        row.setStartedAt(startedAt == null ? Instant.now() : startedAt);
        row.setFinishedAt(Instant.now());
        return row;
    }

    private Long save(BatchRunEntity row, RunKey key) {
        try {
            return repository.save(row).getId();
        } catch (Exception e) {
            log.error("batch_runs: FAILED to persist the run ledger row for {} {} {} (outcome={}) — the run "
                            + "outcome is now only in this log line: {}",
                    key.fileType(), key.settlementWindow(), key.businessDate(), row.getOutcome(),
                    e.getMessage(), e);
            return null;
        }
    }

    /**
     * A bounded stack excerpt: the message chain plus the head of the stack. Deliberately truncated rather
     * than stored whole — 4000 characters is plenty to identify the throw site, and an unbounded blob per
     * failure turns the ledger into the thing that fills the disk.
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
