package com.gme.pay.payment.opsrun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gme.pay.contracts.events.OpsAlertPayload;
import com.gme.pay.events.DomainEvent;
import com.gme.pay.events.EventPublisher;
import com.gme.pay.payment.alert.OpsAlertEvent;
import com.gme.pay.payment.alert.OpsAlertPipeline;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>T2-5 caveat (e): a scheduled ledger-ops run that never happened raises an alert.</b>
 *
 * <p>Everything {@code ledger_ops_runs} could previously tell you was produced by a run that started.
 * A run that never starts writes no row, throws no exception and raises no alert — the table just
 * stops growing, which from inside the service is indistinguishable from a quiet night. These tests
 * drive the detector across that boundary and assert the alert comes out of the <em>existing</em> T3-3
 * pipeline: a real {@link LedgerOpsRunExecutor} over a real {@link OpsAlertPipeline}, with only the
 * transport swapped for a capturing publisher. No second alerting path is asserted into existence.
 *
 * <p>The repository is stubbed because the input under test is "what the table last recorded", which
 * is a single timestamp; the pruner's SQL is exercised against a real database in
 * {@code LedgerOpsRunRetentionTest}.
 */
class MissedLedgerOpsRunMonitorTest {

    private static final Instant NOW = Instant.parse("2026-07-28T12:00:00Z");

    private final LedgerOpsRunRepository repository = mock(LedgerOpsRunRepository.class);
    private final List<OpsAlertPayload> raised = new ArrayList<>();

    private final LedgerOpsRunExecutor executor = new LedgerOpsRunExecutor(
            mock(LedgerOpsRunRecorder.class),
            new OpsAlertPipeline((EventPublisher) (DomainEvent event) -> {
                if (event instanceof OpsAlertEvent alert) {
                    raised.add(alert.getPayload());
                }
            }));

    /**
     * Builds a monitor armed at {@code armedAt}, then advances its clock to {@code NOW} so the
     * "armed then, checked now" timeline exists without sleeping.
     */
    private MissedLedgerOpsRunMonitor monitor(Instant armedAt, Duration cooldown) {
        MutableClock clock = new MutableClock(armedAt);
        MissedLedgerOpsRunMonitor built = new MissedLedgerOpsRunMonitor(
                repository, executor, clock, true, true, true,
                Duration.ofMinutes(30), Duration.ofHours(26), Duration.ofHours(26), cooldown);
        clock.set(NOW);
        clocks.add(clock);
        return built;
    }

    /** The clock handed to the most recently built monitor, so a test can move time forward. */
    private final List<MutableClock> clocks = new ArrayList<>();

    /** A {@link Clock} whose instant can be moved — the smallest way to test a cooldown expiring. */
    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void set(Instant value) {
            this.instant = value;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private void lastRun(String job, Instant startedAt) {
        LedgerOpsRunEntity row = new LedgerOpsRunEntity();
        row.setJob(job);
        row.setOutcome(LedgerOpsRunOutcome.SUCCESS.name());
        row.setTriggerSource(LedgerOpsRunTrigger.SCHEDULER.name());
        row.setStartedAt(startedAt);
        row.setFinishedAt(startedAt);
        when(repository.findFirstByJobOrderByStartedAtDescIdDesc(job)).thenReturn(Optional.of(row));
    }

    private void allJobsHealthy() {
        lastRun(LedgerOpsJob.REVENUE_POSTING_REPLAY, NOW.minus(Duration.ofMinutes(4)));
        lastRun(LedgerOpsJob.DAY_CLOSE, NOW.minus(Duration.ofHours(10)));
        lastRun(LedgerOpsJob.FX_EXPOSURE, NOW.minus(Duration.ofHours(9)));
    }

    // ---------------------------------------------------------------------------------------------
    // The detection itself
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a replay sweep that stopped running raises LEDGER_OPS_RUN_MISSED through the T3-3 pipeline")
    void aStoppedJobIsAlerted() {
        allJobsHealthy();
        // The 5-minute sweeper last ran 45 minutes ago: not a late cycle, a stopped job.
        lastRun(LedgerOpsJob.REVENUE_POSTING_REPLAY, NOW.minus(Duration.ofMinutes(45)));

        List<String> alerted = monitor(NOW.minus(Duration.ofDays(2)), Duration.ofHours(6)).check();

        assertThat(alerted).containsExactly(LedgerOpsJob.REVENUE_POSTING_REPLAY);
        assertThat(raised).hasSize(1);
        OpsAlertPayload alert = raised.get(0);
        assertThat(alert.alertType()).isEqualTo(MissedLedgerOpsRunMonitor.ALERT_TYPE);
        assertThat(alert.severity()).isEqualTo("CRITICAL");
        assertThat(alert.subjectRef()).isEqualTo(LedgerOpsJob.REVENUE_POSTING_REPLAY);
        assertThat(alert.eventType())
                .as("it must travel as an ops.alert on the existing pipeline, not a new event type")
                .isEqualTo(OpsAlertPayload.EVENT_TYPE);
        assertThat(alert.detail())
                .as("the detail has to say the run did not happen — 'failed' would send the reader "
                        + "hunting for an exception that does not exist")
                .contains("has not run for")
                .contains("the run did not happen");
    }

    @Test
    @DisplayName("a job running normally raises nothing")
    void aHealthyFleetIsSilent() {
        allJobsHealthy();

        assertThat(monitor(NOW.minus(Duration.ofDays(2)), Duration.ofHours(6)).check()).isEmpty();
        assertThat(raised).isEmpty();
    }

    @Test
    @DisplayName("a late cycle is not an incident: one missed 5-minute sweep stays silent")
    void oneMissedCycleIsNotAnAlert() {
        allJobsHealthy();
        // 12 minutes = two missed cycles. The window is six, deliberately, so a restart or a long
        // sweep does not page anyone.
        lastRun(LedgerOpsJob.REVENUE_POSTING_REPLAY, NOW.minus(Duration.ofMinutes(12)));

        assertThat(monitor(NOW.minus(Duration.ofDays(2)), Duration.ofHours(6)).check()).isEmpty();
    }

    @Test
    @DisplayName("a job that has NEVER run is reported once its window elapses after process start")
    void aJobThatNeverRanIsReported() {
        // No stubbing: findFirst* returns Optional.empty() for every job.
        when(repository.findFirstByJobOrderByStartedAtDescIdDesc(anyString()))
                .thenReturn(Optional.empty());

        List<String> alerted = monitor(NOW.minus(Duration.ofDays(2)), Duration.ofHours(6)).check();

        assertThat(alerted).containsExactlyInAnyOrder(
                LedgerOpsJob.REVENUE_POSTING_REPLAY, LedgerOpsJob.DAY_CLOSE, LedgerOpsJob.FX_EXPOSURE);
        assertThat(raised).allSatisfy(alert -> assertThat(alert.detail())
                .as("'no run has ever been recorded' and 'the last one was a while ago' are "
                        + "different incidents with different first questions")
                .contains("NO run has ever been recorded"));
    }

    @Test
    @DisplayName("a freshly started process does not alert about a day-close that is not due yet")
    void aColdStartDoesNotAlertBeforeTheWindowElapses() {
        when(repository.findFirstByJobOrderByStartedAtDescIdDesc(anyString()))
                .thenReturn(Optional.empty());

        // Armed 20 minutes ago with no history at all: the replay window (30m) has not elapsed and
        // neither have the two 26h daily windows. Without the process-start baseline every cold start
        // would page about three jobs that were simply not due.
        assertThat(monitor(NOW.minus(Duration.ofMinutes(20)), Duration.ofHours(6)).check()).isEmpty();
        assertThat(raised).isEmpty();
    }

    @Test
    @DisplayName("the cooldown suppresses a repeat, and releases once the window has passed")
    void repeatsAreSuppressedByTheCooldownAndReleaseAfterIt() {
        allJobsHealthy();
        lastRun(LedgerOpsJob.REVENUE_POSTING_REPLAY, NOW.minus(Duration.ofHours(3)));

        MissedLedgerOpsRunMonitor suppressing =
                monitor(NOW.minus(Duration.ofDays(2)), Duration.ofHours(6));
        MutableClock clock = clocks.get(clocks.size() - 1);

        assertThat(suppressing.check()).hasSize(1);
        clock.set(NOW.plus(Duration.ofHours(1)));
        assertThat(suppressing.check())
                .as("a 15-minute check cadence against a 6-hour outage would otherwise raise 24 "
                        + "identical alerts")
                .isEmpty();

        clock.set(NOW.plus(Duration.ofHours(7)));
        assertThat(suppressing.check())
                .as("...but the job is still silent, so the alert must come back rather than be "
                        + "suppressed forever by one earlier firing")
                .containsExactly(LedgerOpsJob.REVENUE_POSTING_REPLAY);
        assertThat(raised).hasSize(2);
    }

    @Test
    @DisplayName("a disabled job is not monitored — its silence is intended")
    void aDisabledJobIsNotMonitored() {
        when(repository.findFirstByJobOrderByStartedAtDescIdDesc(anyString()))
                .thenReturn(Optional.empty());

        MissedLedgerOpsRunMonitor dayCloseOff = new MissedLedgerOpsRunMonitor(
                repository, executor, new MutableClock(NOW), true, false, true,
                Duration.ofMinutes(30), Duration.ofHours(26), Duration.ofHours(26),
                Duration.ofHours(6));

        assertThat(dayCloseOff.expectations()).doesNotContainKey(LedgerOpsJob.DAY_CLOSE);
        assertThat(dayCloseOff.check())
                .as("paging about a deliberately-disabled job is how an alert type becomes noise")
                .doesNotContain(LedgerOpsJob.DAY_CLOSE);
    }

    // ---------------------------------------------------------------------------------------------
    // Coverage: a new job cannot be added without a decision about whether its absence matters
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("every LedgerOpsJob constant is either monitored or explicitly declared unscheduled")
    void everyJobIsClassified() throws Exception {
        Set<String> allJobs = new LinkedHashSet<>();
        for (Field field : LedgerOpsJob.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                allJobs.add((String) field.get(null));
            }
        }
        assertThat(allJobs).isNotEmpty();

        MissedLedgerOpsRunMonitor all = new MissedLedgerOpsRunMonitor(
                repository, executor, Clock.fixed(NOW, ZoneOffset.UTC), true, true, true,
                Duration.ofMinutes(30), Duration.ofHours(26), Duration.ofHours(26),
                Duration.ofHours(6));

        Set<String> classified = new LinkedHashSet<>(all.expectations().keySet());
        classified.addAll(MissedLedgerOpsRunMonitor.NOT_SCHEDULED);

        Set<String> unclassified = new LinkedHashSet<>(allJobs);
        unclassified.removeAll(classified);
        assertThat(unclassified)
                .as("a new ledger-ops job must be given a max-silence window in "
                        + "MissedLedgerOpsRunMonitor, or added to NOT_SCHEDULED with the reason. "
                        + "Silently unmonitored is how the run ledger got a fourth writer and no "
                        + "reader in the first place")
                .isEmpty();

        assertThat(MissedLedgerOpsRunMonitor.NOT_SCHEDULED)
                .as("the operator requeue is never scheduled, so 'nobody requeued anything today' "
                        + "is the normal state and must never alert")
                .containsExactly(LedgerOpsJob.REVENUE_POSTING_REQUEUE);
    }
}
