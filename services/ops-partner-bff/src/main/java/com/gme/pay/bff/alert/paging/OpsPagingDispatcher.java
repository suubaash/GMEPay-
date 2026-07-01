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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
 * bypass the dedupe check via {@link #escalate}.
 *
 * <h2>Single-fire across replicas</h2>
 * Paging-on-consume is naturally single-fire across replicas: the Kafka consumer group
 * delivers each record to exactly one consumer, so only one replica pages. (The dedupe map
 * is per-replica; the consumer-group guarantee — not the map — is what prevents
 * cross-replica double paging on the consume path.)
 */
@Component
public class OpsPagingDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OpsPagingDispatcher.class);

    private final PagingPort pagingPort;
    private final OpsAlertStore store;
    private final int minSeverityRank;
    private final Duration dedupeWindow;
    private final String link;
    private final Clock clock;

    /** key = alertType|subjectRef → last successful page instant (for cooldown). */
    private final Map<String, Instant> lastPaged = new ConcurrentHashMap<>();

    @Autowired
    public OpsPagingDispatcher(
            PagingPort pagingPort,
            OpsAlertStore store,
            @Value("${gmepay.ops.paging.min-severity:CRITICAL}") String minSeverity,
            @Value("${gmepay.ops.paging.dedupe-window:15m}") Duration dedupeWindow,
            @Value("${gmepay.ops.paging.link-base:}") String linkBase) {
        this(pagingPort, store, minSeverity, dedupeWindow, linkBase, Clock.systemUTC());
    }

    OpsPagingDispatcher(PagingPort pagingPort, OpsAlertStore store, String minSeverity,
                        Duration dedupeWindow, String linkBase, Clock clock) {
        this.pagingPort = pagingPort;
        this.store = store;
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
        Instant last = lastPaged.get(key);
        if (last != null && Duration.between(last, now).compareTo(dedupeWindow) < 0) {
            log.info("ops paging SUPPRESSED (dedupe {} left) type={} subjectRef={}",
                    dedupeWindow.minus(Duration.between(last, now)), alert.alertType(), alert.subjectRef());
            return record(alert, "SUPPRESSED", "log", null, now);
        }
        return dispatch(alert, now, key);
    }

    /**
     * Escalation re-page of a still-open CRITICAL alert. Cooldown still applies (so an
     * escalation sweep that runs more often than the window does not storm), but the
     * severity threshold is assumed already met by the caller.
     */
    public OpsAlertView escalate(OpsAlertView alert) {
        String key = key(alert);
        Instant now = clock.instant();
        Instant last = lastPaged.get(key);
        if (last != null && Duration.between(last, now).compareTo(dedupeWindow) < 0) {
            return alert; // within cooldown — skip this escalation tick
        }
        return dispatch(alert, now, key);
    }

    private OpsAlertView dispatch(OpsAlertView alert, Instant now, String key) {
        PagingPort.PageOutcome outcome = pagingPort.page(PageRequest.from(alert, link));
        String status = outcome.delivered() ? "DELIVERED" : "FAILED";
        if (outcome.delivered()) {
            lastPaged.put(key, now); // only a delivered page opens the cooldown
        }
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
