package com.gme.pay.scheme.zeropay.ops;

import com.gme.pay.scheme.zeropay.ops.calendar.BusinessDayVerdict;
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
 * Writes one {@code zp_batch_runs} row per ZeroPay batch-window run (gap <b>T3-4</b>).
 *
 * <p>{@link Propagation#REQUIRES_NEW} for the same reason as settlement-reconciliation's
 * {@code BatchRunRecorder}: the row must commit on its own connection so it survives the rollback of
 * whatever the run was doing (here, the {@code ZpBatchRegistrar} writes around generate/transfer). A write
 * that joined a rollback-marked transaction would be discarded with it, or fail outright with
 * {@code UnexpectedRollbackException} — either way reproducing the invisibility being fixed.
 *
 * <p><b>Never throws.</b> A recorder that threw inside a catch block would replace the original exception
 * with a bookkeeping error. On failure it logs and returns {@code null}, and the alert still goes out with
 * {@code zp_batch_runs.id=UNWRITTEN}.
 */
@Component
public class ZpBatchRunRecorder {

    private static final int TRACE_LIMIT = 4000;

    private static final Logger log = LoggerFactory.getLogger(ZpBatchRunRecorder.class);

    private final ZpBatchRunRepository repository;

    public ZpBatchRunRecorder(ZpBatchRunRepository repository) {
        this.repository = repository;
    }

    /** One run's coordinates plus the calendar verdict in force for it. */
    public record RunKey(String batchType, LocalDate businessDate, ZpBatchRunTrigger trigger,
                         BusinessDayVerdict verdict, String operatorId, String reason) {

        public RunKey(String batchType, LocalDate businessDate, ZpBatchRunTrigger trigger,
                      BusinessDayVerdict verdict) {
            this(batchType, businessDate, trigger, verdict, null, null);
        }
    }

    /** Record a FAILED run — committed in its own transaction so it survives the caller's rollback. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long recordFailure(RunKey key, Instant startedAt, Throwable cause) {
        ZpBatchRunEntity row = base(key, ZpBatchRunOutcome.FAILED, startedAt);
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

    /** Record a SUCCESSFUL run, so "did last night's six windows happen?" is answerable from one table. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long recordSuccess(RunKey key, Instant startedAt, Integer recordCount, Boolean transferred) {
        ZpBatchRunEntity row = base(key, ZpBatchRunOutcome.SUCCESS, startedAt);
        row.setRecordCount(recordCount);
        row.setTransferred(transferred);
        return save(row, key);
    }

    /** Record a run the calendar or the feature gate deliberately did not perform. Not an incident. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long recordSkipped(RunKey key, Instant startedAt, ZpBatchRunOutcome outcome, String why) {
        ZpBatchRunEntity row = base(key, outcome, startedAt);
        row.setFailureMessage(why);
        row.setAlertStatus(ZpBatchRunAlerter.Outcome.NOT_APPLICABLE);
        return save(row, key);
    }

    /** Stamp the notification outcome on an already-written row: one row, both questions. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAlertOutcome(Long runId, ZpBatchRunAlerter.Outcome outcome) {
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
            log.error("zp_batch_runs: could not stamp alert outcome {} on run {}: {}",
                    outcome.status(), runId, e.getMessage(), e);
        }
    }

    private ZpBatchRunEntity base(RunKey key, ZpBatchRunOutcome outcome, Instant startedAt) {
        ZpBatchRunEntity row = new ZpBatchRunEntity();
        row.setBatchType(key.batchType());
        row.setBusinessDate(key.businessDate());
        row.setOutcome(outcome.name());
        row.setTriggerSource(key.trigger().name());
        row.setCalendarVerdict(
                (key.verdict() == null ? BusinessDayVerdict.UNVERIFIED : key.verdict()).name());
        row.setOperatorId(key.operatorId());
        row.setReason(key.reason());
        row.setStartedAt(startedAt == null ? Instant.now() : startedAt);
        row.setFinishedAt(Instant.now());
        return row;
    }

    private Long save(ZpBatchRunEntity row, RunKey key) {
        try {
            return repository.save(row).getId();
        } catch (Exception e) {
            log.error("zp_batch_runs: FAILED to persist the run ledger row for {} {} (outcome={}) — the run "
                            + "outcome is now only in this log line: {}",
                    key.batchType(), key.businessDate(), row.getOutcome(), e.getMessage(), e);
            return null;
        }
    }

    /** Bounded stack excerpt — enough to identify the throw site without letting the ledger fill the disk. */
    static String stackExcerpt(Throwable cause) {
        StringWriter sw = new StringWriter();
        try (PrintWriter pw = new PrintWriter(sw)) {
            cause.printStackTrace(pw);
        }
        String full = sw.toString();
        return full.length() <= TRACE_LIMIT ? full : full.substring(0, TRACE_LIMIT);
    }
}
