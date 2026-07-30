package com.gme.pay.settlement.runlog;

import com.gme.pay.settlement.calendar.BusinessCalendar;
import com.gme.pay.settlement.calendar.BusinessDayVerdict;
import com.gme.pay.settlement.calendar.NonBusinessDayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.function.Function;

/**
 * The single wrapper every batch-window run goes through — scheduled or operator-triggered (gap
 * <b>T3-4</b>).
 *
 * <p>It exists so the four behaviours the gap asked for are implemented <b>once</b> instead of copied into
 * eight catch blocks that would drift apart:
 * <ol>
 *   <li>consult the configured business-day calendar and skip a configured non-business date;</li>
 *   <li>make an UNVERIFIED calendar visible (alert + a stamped verdict on every run row);</li>
 *   <li>persist a durable run row in its OWN transaction, so a FAILED run survives its own rollback;</li>
 *   <li>raise the failure through the existing {@code gmepay.ops.alert} pipeline and record whether that
 *       notification actually left the service.</li>
 * </ol>
 *
 * <p>The operator re-run path goes through this same method, so a re-run is recorded, calendar-checked and
 * alerted exactly like a scheduled run — there is no "manual runs are invisible" second class.
 *
 * <p><b>This method never throws for a business reason.</b> It returns a {@link RunResult} the caller
 * inspects. That is what turns "log-and-swallow" into "record-and-report": the swallow still happens (a
 * failed 05:00 must not stop the 14:00 window or kill the scheduler thread) but it is no longer silent.
 */
@Component
public class BatchRunExecutor {

    private static final Logger log = LoggerFactory.getLogger(BatchRunExecutor.class);

    private final BusinessCalendar calendar;
    private final BatchRunRecorder recorder;
    private final BatchRunAlerter alerter;

    public BatchRunExecutor(BusinessCalendar calendar, BatchRunRecorder recorder, BatchRunAlerter alerter) {
        this.calendar = calendar;
        this.recorder = recorder;
        this.alerter = alerter;
    }

    /** What a run produced, for the ledger row. All fields optional. */
    public record RunSummary(String batchId, String batchStatus, Integer recordCount) {

        public static final RunSummary EMPTY = new RunSummary(null, null, null);
    }

    /** The work of one window. May throw anything; the executor converts it into a recorded failure. */
    @FunctionalInterface
    public interface BatchWork<T> {
        T run() throws Exception;
    }

    /**
     * The outcome of one wrapped run.
     *
     * @param outcome what happened
     * @param value   the work's return value on SUCCESS, else null
     * @param runId   the {@code batch_runs.id} written for this run, or null if the ledger write failed
     * @param failure the exception on FAILED, else null
     * @param verdict the calendar verdict in force for the business date
     */
    public record RunResult<T>(BatchRunOutcome outcome, T value, Long runId, Throwable failure,
                               BusinessDayVerdict verdict) {

        public boolean succeeded() {
            return outcome == BatchRunOutcome.SUCCESS;
        }

        public boolean skipped() {
            return outcome == BatchRunOutcome.SKIPPED_NON_BUSINESS_DAY
                    || outcome == BatchRunOutcome.SKIPPED_DISABLED;
        }
    }

    /**
     * Run one window with calendar gating, durable run recording and failure alerting.
     *
     * @param runKind  {@link BatchRunRecorder#KIND_GENERATION} or {@link BatchRunRecorder#KIND_RECON}
     * @param fileType e.g. {@code ZP0061}
     * @param window   e.g. {@code MORNING}; used with fileType + date as the run's identity
     * @param date     business date (KST)
     * @param trigger  scheduler or operator re-run
     * @param work     the actual window
     * @param describe extracts the ledger summary from the work's result; may be null
     */
    public <T> RunResult<T> execute(String runKind, String fileType, String window, LocalDate date,
                                    BatchRunTrigger trigger, BatchWork<T> work,
                                    Function<T, RunSummary> describe) {
        return execute(runKind, fileType, window, date, trigger, work, describe, null, null);
    }

    /**
     * Same as {@link #execute(String, String, String, LocalDate, BatchRunTrigger, BatchWork, Function)} with
     * operator attribution, used by the re-run API so {@code batch_runs.operator_id} / {@code reason} record
     * who asked for the run and why.
     */
    public <T> RunResult<T> execute(String runKind, String fileType, String window, LocalDate date,
                                    BatchRunTrigger trigger, BatchWork<T> work,
                                    Function<T, RunSummary> describe,
                                    String operatorId, String reason) {
        Instant startedAt = Instant.now();
        BusinessDayVerdict verdict = calendar.classify(date);

        // (1) Calendar gate. gate() re-derives the verdict and throws for a configured non-business date,
        //     or for UNVERIFIED when gmepay.calendar.fail-closed=true.
        try {
            verdict = calendar.gate(date, fileType + "/" + window);
        } catch (NonBusinessDayException e) {
            BatchRunRecorder.RunKey key =
                    new BatchRunRecorder.RunKey(runKind, fileType, window, date, trigger, verdict,
                            operatorId, reason);
            Long runId = recorder.recordSkipped(key, startedAt,
                    BatchRunOutcome.SKIPPED_NON_BUSINESS_DAY, e.getMessage());
            log.info("batch window {}/{} for {} SKIPPED: {}", fileType, window, date, e.getMessage());
            return new RunResult<>(BatchRunOutcome.SKIPPED_NON_BUSINESS_DAY, null, runId, null, verdict);
        }

        // (2) Absence of calendar data is reported, never assumed away. De-duplicated to one alert per
        //     business date inside the alerter; the per-run evidence is the verdict on every row below.
        if (verdict.isUnverified()) {
            log.warn("batch window {}/{} running on {} with an UNVERIFIED business-day calendar ({}) — "
                            + "no KRW banking-holiday check was actually performed",
                    fileType, window, date, calendar.describe());
            alerter.alertCalendarUnverified(fileType, window, date);
        }

        BatchRunRecorder.RunKey key =
                new BatchRunRecorder.RunKey(runKind, fileType, window, date, trigger, verdict,
                            operatorId, reason);

        // (3) + (4) Run; record and report either way.
        try {
            T value = work.run();
            RunSummary summary = (describe == null || value == null) ? RunSummary.EMPTY : describe.apply(value);
            Long runId = recorder.recordSuccess(key, startedAt,
                    summary.batchId(), summary.batchStatus(), summary.recordCount());
            return new RunResult<>(BatchRunOutcome.SUCCESS, value, runId, null, verdict);
        } catch (NonBusinessDayException e) {
            // The work itself consulted the calendar (SettlementBatchJobService does, so a direct call
            // cannot bypass it) and refused. Same treatment as the gate above.
            Long runId = recorder.recordSkipped(key, startedAt,
                    BatchRunOutcome.SKIPPED_NON_BUSINESS_DAY, e.getMessage());
            log.info("batch window {}/{} for {} SKIPPED by the job itself: {}",
                    fileType, window, date, e.getMessage());
            return new RunResult<>(BatchRunOutcome.SKIPPED_NON_BUSINESS_DAY, null, runId, null, verdict);
        } catch (Exception e) {
            // The failure is persisted FIRST (own transaction, survives the job's rollback) so evidence
            // exists before anything that can fail over a network — the ordering payment-executor's
            // OpsAlertPipeline established for T3-3.
            Long runId = recorder.recordFailure(key, startedAt, e);
            BatchRunAlerter.AlertOutcome alert = alerter.alertRunFailed(runId, fileType, window, date, e);
            recorder.recordAlertOutcome(runId, alert);
            log.error("batch window {}/{} for {} FAILED (batch_runs.id={}, alert={}): {}",
                    fileType, window, date, runId, alert.status(), e.getMessage(), e);
            return new RunResult<>(BatchRunOutcome.FAILED, null, runId, e, verdict);
        }
    }

    /** Record a window that a feature gate turned off, without running or alerting. */
    public void recordDisabled(String runKind, String fileType, String window, LocalDate date, String why) {
        BatchRunRecorder.RunKey key = new BatchRunRecorder.RunKey(
                runKind, fileType, window, date, BatchRunTrigger.SCHEDULER, calendar.classify(date));
        recorder.recordSkipped(key, Instant.now(), BatchRunOutcome.SKIPPED_DISABLED, why);
    }

    public BusinessCalendar getCalendar() {
        return calendar;
    }
}
