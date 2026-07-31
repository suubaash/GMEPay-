package com.gme.pay.scheme.zeropay.ops;

import com.gme.pay.scheme.zeropay.ops.calendar.BusinessCalendar;
import com.gme.pay.scheme.zeropay.ops.calendar.BusinessDayVerdict;
import com.gme.pay.scheme.zeropay.ops.calendar.NonBusinessDayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.function.Function;

/**
 * The single wrapper every ZeroPay batch window goes through — scheduled or operator-triggered (gap
 * <b>T3-4</b>). Counterpart of settlement-reconciliation's {@code BatchRunExecutor}, keyed on
 * {@code (batchType, businessDate)} because ZeroPay's six windows are identified by file type alone.
 *
 * <p>Implements once, for all six crons, what used to be six copies of
 * {@code catch (Exception e) { log.error(...) }}:
 * <ol>
 *   <li>consult the configured business-day calendar; skip a configured non-business date;</li>
 *   <li>make an UNVERIFIED calendar visible (alert once per date + a verdict stamped on every row);</li>
 *   <li>persist a durable {@code zp_batch_runs} row in its OWN transaction;</li>
 *   <li>raise {@code BATCH_RUN_FAILED} on the {@code gmepay.ops.alert} pipeline and record whether that
 *       notification actually left the service.</li>
 * </ol>
 *
 * <p>Never throws for a business reason — the caller inspects the returned {@link RunResult}. The failure is
 * still contained (a failed 02:00 must not stop 05:00 or kill the scheduler thread); it is just no longer
 * silent.
 */
@Component
public class ZpBatchRunExecutor {

    private static final Logger log = LoggerFactory.getLogger(ZpBatchRunExecutor.class);

    private final BusinessCalendar calendar;
    private final ZpBatchRunRecorder recorder;
    private final ZpBatchRunAlerter alerter;

    public ZpBatchRunExecutor(BusinessCalendar calendar, ZpBatchRunRecorder recorder,
                              ZpBatchRunAlerter alerter) {
        this.calendar = calendar;
        this.recorder = recorder;
        this.alerter = alerter;
    }

    /** What a run produced, for the ledger row. */
    public record RunSummary(Integer recordCount, Boolean transferred) {

        public static final RunSummary EMPTY = new RunSummary(null, null);
    }

    /** The work of one window. May throw anything; the executor turns it into a recorded failure. */
    @FunctionalInterface
    public interface BatchWork<T> {
        T run() throws Exception;
    }

    /** The outcome of one wrapped run. */
    public record RunResult<T>(ZpBatchRunOutcome outcome, T value, Long runId, Throwable failure,
                               BusinessDayVerdict verdict) {

        public boolean succeeded() {
            return outcome == ZpBatchRunOutcome.SUCCESS;
        }
    }

    /** Scheduled run — no operator attribution. */
    public <T> RunResult<T> execute(String batchType, LocalDate date, BatchWork<T> work,
                                    Function<T, RunSummary> describe) {
        return execute(batchType, date, ZpBatchRunTrigger.SCHEDULER, work, describe, null, null);
    }

    /** Full form, with the trigger and optional operator attribution. */
    public <T> RunResult<T> execute(String batchType, LocalDate date, ZpBatchRunTrigger trigger,
                                    BatchWork<T> work, Function<T, RunSummary> describe,
                                    String operatorId, String reason) {
        Instant startedAt = Instant.now();
        BusinessDayVerdict verdict = calendar.classify(date);

        // (1) Calendar gate.
        try {
            verdict = calendar.gate(date, batchType);
        } catch (NonBusinessDayException e) {
            ZpBatchRunRecorder.RunKey key =
                    new ZpBatchRunRecorder.RunKey(batchType, date, trigger, verdict, operatorId, reason);
            Long runId = recorder.recordSkipped(key, startedAt,
                    ZpBatchRunOutcome.SKIPPED_NON_BUSINESS_DAY, e.getMessage());
            log.info("ZeroPay batch {} for {} SKIPPED: {}", batchType, date, e.getMessage());
            return new RunResult<>(ZpBatchRunOutcome.SKIPPED_NON_BUSINESS_DAY, null, runId, null, verdict);
        }

        // (2) Absence of calendar data is reported, never assumed away.
        if (verdict.isUnverified()) {
            log.warn("ZeroPay batch {} running on {} with an UNVERIFIED business-day calendar ({}) — no KRW "
                    + "banking-holiday check was actually performed", batchType, date, calendar.describe());
            alerter.alertCalendarUnverified(batchType, date);
        }

        ZpBatchRunRecorder.RunKey key =
                new ZpBatchRunRecorder.RunKey(batchType, date, trigger, verdict, operatorId, reason);

        // (3) + (4) Run; record and report either way.
        try {
            T value = work.run();
            RunSummary summary =
                    (describe == null || value == null) ? RunSummary.EMPTY : describe.apply(value);
            Long runId = recorder.recordSuccess(key, startedAt,
                    summary.recordCount(), summary.transferred());
            return new RunResult<>(ZpBatchRunOutcome.SUCCESS, value, runId, null, verdict);
        } catch (NonBusinessDayException e) {
            Long runId = recorder.recordSkipped(key, startedAt,
                    ZpBatchRunOutcome.SKIPPED_NON_BUSINESS_DAY, e.getMessage());
            return new RunResult<>(ZpBatchRunOutcome.SKIPPED_NON_BUSINESS_DAY, null, runId, null, verdict);
        } catch (Exception e) {
            // Persist FIRST (own transaction), so evidence exists before anything that can fail over a
            // network — the ordering payment-executor's OpsAlertPipeline established for T3-3.
            Long runId = recorder.recordFailure(key, startedAt, e);
            ZpBatchRunAlerter.Outcome alert = alerter.alertRunFailed(runId, batchType, date, e);
            recorder.recordAlertOutcome(runId, alert);
            log.error("ZeroPay batch {} for {} FAILED (zp_batch_runs.id={}, alert={}): {}",
                    batchType, date, runId, alert.status(), e.getMessage(), e);
            return new RunResult<>(ZpBatchRunOutcome.FAILED, null, runId, e, verdict);
        }
    }

    /** Record a window the feature gate turned off, without running or alerting. */
    public void recordDisabled(String batchType, LocalDate date, String why) {
        recorder.recordSkipped(
                new ZpBatchRunRecorder.RunKey(batchType, date, ZpBatchRunTrigger.SCHEDULER,
                        calendar.classify(date)),
                Instant.now(), ZpBatchRunOutcome.SKIPPED_DISABLED, why);
    }
}
