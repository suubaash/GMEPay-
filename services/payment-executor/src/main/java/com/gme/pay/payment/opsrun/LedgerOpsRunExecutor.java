package com.gme.pay.payment.opsrun;

import com.gme.pay.contracts.events.OpsAlertPayload;
import com.gme.pay.payment.alert.OpsAlertPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.function.Function;

/**
 * The single wrapper every ledger-ops run goes through — scheduled or operator-triggered (gap <b>T2-5</b>).
 *
 * <p>It exists so the three behaviours the gap asked for are implemented ONCE instead of copied into six
 * catch blocks that would drift apart:
 * <ol>
 *   <li>persist a durable run row in its OWN transaction, so a FAILED run survives its own rollback
 *       ({@link LedgerOpsRunRecorder});</li>
 *   <li>raise the failure through the EXISTING T3-3 {@code ops.alert} pipeline (persist → publish → notify)
 *       rather than a second alerting path;</li>
 *   <li>record whether that alert was raised, on the same row.</li>
 * </ol>
 *
 * <p>Deliberately NOT included, and stated rather than faked: there is no business-day calendar gate. T3-4's
 * {@code BusinessCalendar} is settlement-reconciliation-local, and all three of these jobs are
 * calendar-independent by design — money moves on Korean banking holidays, so a revenue posting that never
 * landed must still be replayed on a holiday and a holiday must still be closed. Inventing a second calendar
 * here would be a second mechanism; consuming settlement's would be a cross-service dependency for a gate
 * these jobs do not want.
 *
 * <p><b>This method never throws for a business reason.</b> It returns a {@link RunResult} the caller
 * inspects. That is what turns "log-and-swallow" into "record-and-report": the swallow still happens (a failed
 * replay must not kill the scheduler thread) but it is no longer silent.
 */
@Component
public class LedgerOpsRunExecutor {

    /** {@code alertType} for a ledger-ops job that threw. */
    public static final String ALERT_TYPE_RUN_FAILED = "LEDGER_OPS_RUN_FAILED";

    private static final Logger log = LoggerFactory.getLogger(LedgerOpsRunExecutor.class);

    private final LedgerOpsRunRecorder recorder;
    private final OpsAlertPipeline alertPipeline;

    public LedgerOpsRunExecutor(LedgerOpsRunRecorder recorder, OpsAlertPipeline alertPipeline) {
        this.recorder = recorder;
        this.alertPipeline = alertPipeline;
    }

    /** The work of one run. May throw anything; the executor converts it into a recorded failure. */
    @FunctionalInterface
    public interface OpsWork<T> {
        T run() throws Exception;
    }

    /** What one run produced, for the ledger row. */
    public record RunSummary(String summary, @Nullable Integer recordCount) {

        public static final RunSummary EMPTY = new RunSummary(null, null);

        public static RunSummary of(String summary, int recordCount) {
            return new RunSummary(summary, recordCount);
        }
    }

    /**
     * The outcome of one wrapped run.
     *
     * @param outcome what happened
     * @param value   the work's return value on SUCCESS, else null
     * @param runId   the {@code ledger_ops_runs.id} written, or null if the ledger write itself failed
     * @param failure the exception on FAILED, else null
     */
    public record RunResult<T>(LedgerOpsRunOutcome outcome, @Nullable T value, @Nullable Long runId,
                               @Nullable Throwable failure) {

        public boolean succeeded() {
            return outcome == LedgerOpsRunOutcome.SUCCESS;
        }
    }

    /**
     * Run one job with durable run recording and failure alerting.
     *
     * @param key      the run's coordinates (job, business date, trigger, operator)
     * @param work     the actual work
     * @param describe extracts the ledger summary from the work's result; may be null
     */
    public <T> RunResult<T> execute(LedgerOpsRunRecorder.RunKey key, OpsWork<T> work,
                                    @Nullable Function<T, RunSummary> describe) {
        Instant startedAt = Instant.now();
        try {
            T value = work.run();
            RunSummary summary = (describe == null || value == null)
                    ? RunSummary.EMPTY : describe.apply(value);
            Long runId = recorder.recordSuccess(key, startedAt, summary.summary(), summary.recordCount());
            log.info("ledger-ops {} for {} SUCCESS (ledger_ops_runs.id={}): {}",
                    key.job(), key.businessDate(), runId, summary.summary());
            return new RunResult<>(LedgerOpsRunOutcome.SUCCESS, value, runId, null);
        } catch (Exception e) {
            // The failure is persisted FIRST (own transaction, survives the work's rollback) so evidence
            // exists before anything that can fail over a network — the ordering OpsAlertPipeline
            // established for T3-3 and BatchRunExecutor reused for T3-4.
            Long runId = recorder.recordFailure(key, startedAt, e);
            String alertStatus = LedgerOpsRunRecorder.ALERT_EMITTED;
            String alertError = null;
            try {
                alertPipeline.emit(new OpsAlertPayload(
                        OpsAlertPayload.EVENT_TYPE,
                        ALERT_TYPE_RUN_FAILED,
                        "CRITICAL",
                        key.job(),
                        "ledger-ops job " + key.job() + " FAILED"
                                + (key.businessDate() == null ? "" : " for " + key.businessDate())
                                + " (ledger_ops_runs.id=" + (runId == null ? "UNWRITTEN" : runId) + "): "
                                + e.getClass().getSimpleName() + ": " + e.getMessage(),
                        Instant.now().toString()));
            } catch (RuntimeException alertFailure) {
                // OpsAlertPipeline.emit() is contractually non-throwing; this is belt-and-braces so a
                // bookkeeping error can never replace the diagnosis of the original failure.
                alertStatus = LedgerOpsRunRecorder.ALERT_EMIT_FAILED;
                alertError = alertFailure.toString();
            }
            recorder.recordAlertOutcome(runId, alertStatus, alertError);
            log.error("ledger-ops {} for {} FAILED (ledger_ops_runs.id={}, alert={}): {}",
                    key.job(), key.businessDate(), runId, alertStatus, e.toString(), e);
            return new RunResult<>(LedgerOpsRunOutcome.FAILED, null, runId, e);
        }
    }

    /** Record a run a feature gate turned off, without running or alerting. */
    public void recordDisabled(LedgerOpsRunRecorder.RunKey key, String why) {
        recorder.recordSkipped(key, Instant.now(), why);
    }

    /** Raise an ops alert that is not itself a run failure (e.g. a poisoned posting). Never throws. */
    public void alert(String alertType, String severity, String subjectRef, String detail) {
        try {
            alertPipeline.emit(new OpsAlertPayload(OpsAlertPayload.EVENT_TYPE, alertType, severity,
                    subjectRef, detail, Instant.now().toString()));
        } catch (RuntimeException e) {
            log.error("failed to raise {} ops alert for subject={}: {}", alertType, subjectRef, e.toString());
        }
    }
}
