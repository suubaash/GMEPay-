package com.gme.pay.bff.alert.paging;

import com.gme.pay.bff.alert.OpsAlertStore;
import com.gme.pay.bff.alert.OpsAlertView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * Decides whether a consumed alert pages a human, and dispatches it via {@link PagingPort},
 * stamping the delivery record ({@link OpsAlertView.Paging}) back onto the stored alert.
 *
 * <h2>Severity policy</h2>
 * Pages only when {@code severity >= gmepay.ops.paging.min-severity} (default
 * {@code CRITICAL}; can be lowered to {@code WARN}). {@code INFO} / unknown / below are
 * stored only. Ordering: {@code INFO < WARN < CRITICAL}.
 *
 * <h2>Dedupe / cooldown</h2>
 * Suppresses a repeat page for the same {@code (alertType + subjectRef)} key within a
 * configurable window ({@code gmepay.ops.paging.dedupe-window}, default 15m) so a
 * re-firing sweep can't storm the pager. A suppressed alert is recorded as
 * {@code SUPPRESSED} on the store (still visible in the alerts list). Escalation re-pages
 * honour the same cooldown via {@link #escalate}.
 *
 * <h2>Single-fire across replicas</h2>
 * The cooldown is now a shared {@link PagingCooldown} (Redis when one is configured), and it is
 * <b>claimed atomically before</b> the page rather than checked and then set. That is what makes
 * the BFF safe to run at N&gt;1 as a pager.
 *
 * <p>The previous note here — "paging-on-consume is naturally single-fire because the Kafka
 * consumer group delivers each record to exactly one consumer" — was true of the consume path and
 * did not cover the two cases that actually page twice:
 * <ol>
 *   <li><b>The escalation sweep runs on every replica</b> and re-pages from that replica's own
 *       alert buffer, with its own per-JVM cooldown map. N replicas, N escalation pages.</li>
 *   <li><b>A re-firing alert consumed by a different replica than last time</b> finds an empty
 *       cooldown map and pages again, well inside the 15-minute window.</li>
 * </ol>
 *
 * <p>The escalation sweep is deliberately <b>not</b> ShedLock-guarded or otherwise pinned to one
 * replica — see {@link OpsPagingEscalationScheduler}, where locking it would be actively wrong.
 *
 * <p>A page that fails to deliver <b>releases</b> the claim, so the pre-existing rule that only a
 * delivered page opens the cooldown survives the change to an atomic claim.
 */
@Component
public class OpsPagingDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OpsPagingDispatcher.class);

    private final PagingPort pagingPort;
    private final OpsAlertStore store;
    private final PagingCooldown cooldown;
    private final int minSeverityRank;
    private final Duration dedupeWindow;
    private final String link;
    private final Clock clock;

    @Autowired
    public OpsPagingDispatcher(
            PagingPort pagingPort,
            OpsAlertStore store,
            PagingCooldown cooldown,
            @Value("${gmepay.ops.paging.min-severity:CRITICAL}") String minSeverity,
            @Value("${gmepay.ops.paging.dedupe-window:15m}") Duration dedupeWindow,
            @Value("${gmepay.ops.paging.link-base:}") String linkBase) {
        this(pagingPort, store, cooldown, minSeverity, dedupeWindow, linkBase, Clock.systemUTC());
    }

    /** Test constructor; defaults the cooldown to the per-JVM implementation. */
    OpsPagingDispatcher(PagingPort pagingPort, OpsAlertStore store, String minSeverity,
                        Duration dedupeWindow, String linkBase, Clock clock) {
        this(pagingPort, store, new InMemoryPagingCooldown(clock), minSeverity, dedupeWindow,
                linkBase, clock);
    }

    OpsPagingDispatcher(PagingPort pagingPort, OpsAlertStore store, PagingCooldown cooldown,
                        String minSeverity, Duration dedupeWindow, String linkBase, Clock clock) {
        this.pagingPort = pagingPort;
        this.store = store;
        this.cooldown = cooldown;
        this.minSeverityRank = rank(minSeverity);
        this.dedupeWindow = dedupeWindow == null || dedupeWindow.isNegative()
                ? Duration.ofMinutes(15) : dedupeWindow;
        this.link = (linkBase == null || linkBase.isBlank()) ? null : linkBase;
        this.clock = clock;
    }

    /**
     * Called after an alert is stored. Pages if the severity meets the threshold and the
     * dedupe window is clear; otherwise stores-only / records SUPPRESSED. Never throws.
     *
     * @return the (possibly paging-stamped) view
     */
    public OpsAlertView onStored(OpsAlertView alert) {
        if (rank(alert.severity()) < minSeverityRank) {
            return alert; // below threshold — stored only, not paged
        }
        String key = key(alert);
        Instant now = clock.instant();
        if (!cooldown.tryClaim(key, dedupeWindow)) {
            log.info("ops paging SUPPRESSED (inside the {} dedupe window, fleet-wide) type={} "
                            + "subjectRef={}", dedupeWindow, alert.alertType(), alert.subjectRef());
            return record(alert, "SUPPRESSED", "log", null, now);
        }
        return dispatch(alert, now, key);
    }

    /**
     * Escalation re-page of a still-open CRITICAL alert. The cooldown still applies (so an
     * escalation sweep that runs more often than the window does not storm, and so N replicas
     * sweeping their own buffers page once between them), but the severity threshold is assumed
     * already met by the caller.
     */
    public OpsAlertView escalate(OpsAlertView alert) {
        String key = key(alert);
        Instant now = clock.instant();
        if (!cooldown.tryClaim(key, dedupeWindow)) {
            return alert; // within cooldown — skip this escalation tick
        }
        return dispatch(alert, now, key);
    }

    private OpsAlertView dispatch(OpsAlertView alert, Instant now, String key) {
        PagingPort.PageOutcome outcome;
        try {
            outcome = pagingPort.page(PageRequest.from(alert, link));
        } catch (RuntimeException e) {
            // The claim is held; releasing it keeps "only a delivered page opens the cooldown"
            // true even when the port throws instead of answering not-delivered.
            cooldown.release(key);
            throw e;
        }
        if (!outcome.delivered()) {
            // Release, so the next tick or another replica may retry rather than the key being
            // silenced for the whole window by a failed attempt.
            cooldown.release(key);
        }
        String status = outcome.delivered() ? "DELIVERED" : "FAILED";
        return record(alert, status, outcome.channel(), outcome.detail(), now);
    }

    private OpsAlertView record(OpsAlertView alert, String status, String channel,
                                String detail, Instant now) {
        int priorAttempts = alert.paging() == null ? 0 : alert.paging().attempts();
        int attempts = "SUPPRESSED".equals(status) ? priorAttempts : priorAttempts + 1;
        OpsAlertView.Paging p =
                new OpsAlertView.Paging(status, channel, attempts, now.toString(), detail);
        return store.update(alert.seq(), a -> a.withPaging(p)).orElse(alert.withPaging(p));
    }

    private static String key(OpsAlertView a) {
        return (a.alertType() == null ? "" : a.alertType()) + "|"
                + (a.subjectRef() == null ? "" : a.subjectRef());
    }

    /** {@code INFO < WARN < CRITICAL}; unknown/null ranks below INFO so it never pages. */
    static int rank(String severity) {
        if (severity == null) {
            return -1;
        }
        return switch (severity.trim().toUpperCase(Locale.ROOT)) {
            case "CRITICAL" -> 3;
            case "WARN", "WARNING" -> 2;
            case "INFO" -> 1;
            default -> -1;
        };
    }
}
