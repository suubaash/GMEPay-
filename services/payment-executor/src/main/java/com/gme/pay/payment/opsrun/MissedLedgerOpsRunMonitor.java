package com.gme.pay.payment.opsrun;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects a scheduled ledger-ops job that <b>did not run</b> and raises it through the existing T3-3
 * ops-alert pipeline (T2-5 caveat (e)).
 *
 * <h2>Why absence needs its own detector</h2>
 *
 * <p>{@link LedgerOpsRunExecutor} makes a failed run loud: it persists a FAILED row and raises
 * {@code LEDGER_OPS_RUN_FAILED}. But every one of those signals is produced <em>by the run itself</em>.
 * A run that never starts produces none of them — no row, no exception, no alert — and
 * {@code ledger_ops_runs} simply stops growing. For a scheduled financial job that is the failure mode
 * that matters: not a loud error, a silent absence. A crashed scheduler thread, a ShedLock row wedged
 * by a pod that died holding it, a {@code @ConditionalOnProperty} flipped off in one environment and
 * forgotten, a cron expression edited into a window that never fires — all of them look exactly like
 * "nothing has gone wrong" from inside this service.
 *
 * <p>The POISON-requeue work made this worse in the ordinary way: it added a fourth writer to the
 * table without adding any reader that notices a writer going quiet.
 *
 * <h2>What it does</h2>
 *
 * <p>Every {@code gmepay.ledger-ops.missed-run.check-interval-ms} it asks, per expected job, "how long
 * since this job last recorded ANY run?" and raises {@value #ALERT_TYPE} past that job's configured
 * maximum silence. The alert goes through {@link LedgerOpsRunExecutor#alert} — the same
 * {@code OpsAlertPipeline} (persist to {@code ops_alerts} → publish → notify) that carries every other
 * payment-executor alert. No second alerting path is introduced.
 *
 * <h2>The numbers are engineering defaults, not commitments</h2>
 *
 * <p><b>Nothing here is a service-level objective and no partner or regulator is promised any of it.</b>
 * Each threshold is a property, and each default is set at roughly a small multiple of the job's own
 * cadence so that one skipped cycle is not an incident but a stopped job is:
 *
 * <table><caption>Shipped defaults</caption>
 *   <tr><th>Job</th><th>Cadence</th><th>Default max silence</th><th>Reasoning</th></tr>
 *   <tr><td>{@code REVENUE_POSTING_REPLAY}</td><td>5 min</td><td>PT30M</td>
 *       <td>six missed cycles — long enough to ride out a restart or a long sweep, short enough that
 *           unbooked revenue is noticed the same hour.</td></tr>
 *   <tr><td>{@code DAY_CLOSE}</td><td>daily 02:30 KST</td><td>PT26H</td>
 *       <td>one day plus two hours of slack, so a late run is not an alert but a skipped night is —
 *           detected the same morning rather than at month-end.</td></tr>
 *   <tr><td>{@code FX_EXPOSURE}</td><td>daily 03:00 KST</td><td>PT26H</td><td>as above.</td></tr>
 * </table>
 *
 * <p><b>An owner should confirm all three</b> against how quickly finance actually needs to know, and
 * against on-call tolerance. They are chosen to be defensible, not authoritative.
 *
 * <h2>Deliberate exclusions</h2>
 *
 * <ul>
 *   <li>{@link LedgerOpsJob#REVENUE_POSTING_REQUEUE} is <b>never</b> expected. It is an operator act;
 *       "no human requeued anything today" is the normal state and alerting on it would train people
 *       to ignore this alert type. {@code LedgerOpsJobExpectationsTest} pins that this exclusion is a
 *       decision rather than an omission, and fails if a new job constant is added to neither list.</li>
 *   <li>A job whose own {@code enabled} flag is {@code false} is not monitored. Its scheduler bean does
 *       not exist, so its silence is intended; paging about a deliberately-disabled job is how an alert
 *       becomes noise. The monitor reads the same three flags the schedulers do, so the two cannot
 *       disagree.</li>
 *   <li>Outcome is not considered. A FAILED run counts as "ran" — it has its own alert. This detector
 *       is only ever about silence.</li>
 * </ul>
 *
 * <h2>Two honest limits</h2>
 *
 * <ol>
 *   <li><b>Freshly started process.</b> With no history at all, "never ran" is measured from when this
 *       bean was created, not from the epoch — otherwise every cold start would alert about a day-close
 *       that was simply not due yet. A deployment that then never runs its jobs still alerts, one max-
 *       silence window after boot.</li>
 *   <li><b>The cooldown is per-JVM.</b> {@code gmepay.ledger-ops.missed-run.cooldown} (default 6 h)
 *       suppresses repeats, and that state is in memory, so a restarting pod can re-alert sooner. That
 *       matches how {@code DeclineSpikeMonitor}'s cooldown already behaves in this service; re-alerting
 *       is the safe direction for a detector whose whole job is to break silence.</li>
 * </ol>
 */
@Component
@ConditionalOnProperty(name = "gmepay.ledger-ops.missed-run.enabled", havingValue = "true",
        matchIfMissing = true)
public class MissedLedgerOpsRunMonitor {

    /** {@code alertType} raised when an expected run has not happened. */
    public static final String ALERT_TYPE = "LEDGER_OPS_RUN_MISSED";

    /**
     * Same severity as {@code LEDGER_OPS_RUN_FAILED}, deliberately. A run that failed left a diagnosis
     * behind; a run that never happened left nothing at all, so it is not the lesser of the two.
     */
    public static final String SEVERITY = "CRITICAL";

    /**
     * Jobs that are NEVER expected on a schedule, and must therefore never be reported as missed.
     * Consulted by the coverage test so the exclusion stays a stated decision.
     */
    public static final List<String> NOT_SCHEDULED = List.of(LedgerOpsJob.REVENUE_POSTING_REQUEUE);

    private static final Logger log = LoggerFactory.getLogger(MissedLedgerOpsRunMonitor.class);

    private final LedgerOpsRunRepository repository;
    private final LedgerOpsRunExecutor executor;
    private final Clock clock;
    private final Duration cooldown;

    /** job → the longest silence tolerated before an alert. Only monitored (enabled) jobs are present. */
    private final Map<String, Duration> expectations;

    /**
     * No history means "not seen since this process started", not "not seen since the epoch" — see the
     * class javadoc.
     */
    private final Instant armedAt;

    /** job → when this JVM last alerted about it, for the cooldown. */
    private final Map<String, Instant> lastAlertAt = new ConcurrentHashMap<>();

    public MissedLedgerOpsRunMonitor(
            LedgerOpsRunRepository repository,
            LedgerOpsRunExecutor executor,
            Clock clock,
            // The SAME flags the three schedulers are gated on, so a disabled job is never "missed".
            @Value("${gmepay.revenue-posting-replay.enabled:true}") boolean replayEnabled,
            @Value("${gmepay.day-close.enabled:true}") boolean dayCloseEnabled,
            @Value("${gmepay.fx-exposure.enabled:true}") boolean fxExposureEnabled,
            @Value("${gmepay.ledger-ops.missed-run.max-silence.revenue-posting-replay:PT30M}")
            Duration replayMaxSilence,
            @Value("${gmepay.ledger-ops.missed-run.max-silence.day-close:PT26H}")
            Duration dayCloseMaxSilence,
            @Value("${gmepay.ledger-ops.missed-run.max-silence.fx-exposure:PT26H}")
            Duration fxExposureMaxSilence,
            @Value("${gmepay.ledger-ops.missed-run.cooldown:PT6H}") Duration cooldown) {
        this.repository = repository;
        this.executor = executor;
        this.clock = clock;
        this.cooldown = positive(cooldown, Duration.ofHours(6));
        this.armedAt = Instant.now(clock);

        Map<String, Duration> expected = new LinkedHashMap<>();
        if (replayEnabled) {
            expected.put(LedgerOpsJob.REVENUE_POSTING_REPLAY,
                    positive(replayMaxSilence, Duration.ofMinutes(30)));
        }
        if (dayCloseEnabled) {
            expected.put(LedgerOpsJob.DAY_CLOSE, positive(dayCloseMaxSilence, Duration.ofHours(26)));
        }
        if (fxExposureEnabled) {
            expected.put(LedgerOpsJob.FX_EXPOSURE,
                    positive(fxExposureMaxSilence, Duration.ofHours(26)));
        }
        this.expectations = Map.copyOf(expected);

        if (this.expectations.isEmpty()) {
            log.warn("missed-run detection is armed but NO ledger-ops job is enabled — nothing will be "
                    + "monitored. If that is unintended, check gmepay.revenue-posting-replay.enabled, "
                    + "gmepay.day-close.enabled and gmepay.fx-exposure.enabled.");
        } else {
            log.info("missed-run detection armed at {} for {} (cooldown {})",
                    armedAt, this.expectations, this.cooldown);
        }
    }

    /** The jobs this monitor watches and how long each may stay silent. Exposed for the coverage test. */
    public Map<String, Duration> expectations() {
        return expectations;
    }

    /**
     * The periodic check.
     *
     * <p>Cadence is deliberately far shorter than the tightest max-silence window (15 min against
     * 30 min), because detection latency is check-interval + max-silence and a detector that checks as
     * rarely as the thing it is checking doubles its own alerting delay.
     *
     * <p>{@link SchedulerLock} because N replicas would otherwise each raise the same alert for the
     * same silence — and an alert whose count depends on the replica count is one nobody can threshold.
     * {@code lockAtMostFor} is a crash safety net well above the runtime of three indexed reads.
     */
    @Scheduled(
            fixedDelayString = "${gmepay.ledger-ops.missed-run.check-interval-ms:900000}",
            initialDelayString = "${gmepay.ledger-ops.missed-run.initial-delay-ms:300000}")
    @SchedulerLock(name = "MissedLedgerOpsRunMonitor_detectMissedRuns",
            lockAtMostFor = "PT10M", lockAtLeastFor = "PT0S")
    public void detectMissedRuns() {
        try {
            check();
        } catch (RuntimeException e) {
            // A monitor that kills the scheduler thread would take the safety nets down with it.
            log.error("missed-run detection failed: {}", e.toString(), e);
        }
    }

    /**
     * Runs one detection pass and returns the jobs alerted about. Package-visible and synchronous so
     * the test can drive it without a scheduler.
     */
    List<String> check() {
        Instant now = Instant.now(clock);
        List<String> alerted = new ArrayList<>();

        for (Map.Entry<String, Duration> expectation : expectations.entrySet()) {
            String job = expectation.getKey();
            Duration maxSilence = expectation.getValue();

            Optional<LedgerOpsRunEntity> latest =
                    repository.findFirstByJobOrderByStartedAtDescIdDesc(job);
            Instant lastSeen = latest.map(LedgerOpsRunEntity::getStartedAt).orElse(armedAt);
            // A row with a NULL started_at cannot happen (NOT NULL in V008), but a null here would
            // otherwise throw inside the safety net rather than report the job.
            if (lastSeen == null) {
                lastSeen = armedAt;
            }
            Duration silence = Duration.between(lastSeen, now);
            if (silence.compareTo(maxSilence) <= 0) {
                continue;
            }
            if (withinCooldown(job, now)) {
                log.debug("missed run for {} suppressed by the {} cooldown (silent for {})",
                        job, cooldown, silence);
                continue;
            }

            lastAlertAt.put(job, now);
            alerted.add(job);
            executor.alert(ALERT_TYPE, SEVERITY, job, detail(job, latest, lastSeen, silence, maxSilence));
        }
        return alerted;
    }

    private boolean withinCooldown(String job, Instant now) {
        Instant previous = lastAlertAt.get(job);
        return previous != null && Duration.between(previous, now).compareTo(cooldown) < 0;
    }

    private String detail(String job, Optional<LedgerOpsRunEntity> latest, Instant lastSeen,
                          Duration silence, Duration maxSilence) {
        String provenance = latest
                .map(row -> "last run at " + row.getStartedAt() + " (ledger_ops_runs.id=" + row.getId()
                        + ", outcome=" + row.getOutcome() + ")")
                // "no run has EVER been recorded" and "the last one was a while ago" are different
                // incidents with different first questions, so they must not render identically.
                .orElse("NO run has ever been recorded for this job; measured from process start "
                        + lastSeen);
        return "ledger-ops job " + job + " has not run for " + silence
                + ", which exceeds the configured maximum silence of " + maxSilence + " — "
                + provenance + ". Nothing failed: the run did not happen. Check the scheduler thread, "
                + "the ShedLock row for this job, and whether the job's enabled flag was changed.";
    }

    private static Duration positive(Duration value, Duration fallback) {
        return (value == null || value.isZero() || value.isNegative()) ? fallback : value;
    }
}
