package com.gme.pay.bff.alert.paging;

import com.gme.pay.bff.alert.OpsAlertStore;
import com.gme.pay.bff.alert.OpsAlertView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Re-pages CRITICAL alerts that are still un-acked after {@code N} minutes
 * ({@code gmepay.ops.paging.escalation.after}, default 10m). Config-gated and
 * <b>default OFF</b> ({@code gmepay.ops.paging.escalation.enabled}); the scheduler bean is
 * created only when explicitly enabled.
 *
 * <p><b>Replica safety.</b> ops-partner-bff has NO DataSource (it is a stateless REST
 * aggregation BFF that owns no database), so there is no ShedLock table to guard this. The
 * escalation sweep is therefore <b>single-replica-only</b>: run it on exactly one replica
 * (e.g. a dedicated instance with the flag set, or a single-replica deployment). If the BFF
 * is ever given a DataSource, this should be ShedLock-guarded (shedlock + a migration)
 * before enabling on more than one replica. The re-page still honours the dispatcher's
 * cooldown, which bounds duplicate pages even if two replicas ran.
 */
@Component
@ConditionalOnProperty(name = "gmepay.ops.paging.escalation.enabled", havingValue = "true")
public class OpsPagingEscalationScheduler {

    private static final Logger log = LoggerFactory.getLogger(OpsPagingEscalationScheduler.class);

    private final OpsAlertStore store;
    private final OpsPagingDispatcher dispatcher;
    private final Duration escalateAfter;

    public OpsPagingEscalationScheduler(
            OpsAlertStore store,
            OpsPagingDispatcher dispatcher,
            @Value("${gmepay.ops.paging.escalation.after:10m}") Duration escalateAfter) {
        this.store = store;
        this.dispatcher = dispatcher;
        this.escalateAfter = escalateAfter == null || escalateAfter.isNegative()
                ? Duration.ofMinutes(10) : escalateAfter;
        log.info("ops paging escalation ENABLED (after={}, single-replica-only — no DataSource/ShedLock)",
                this.escalateAfter);
    }

    /**
     * Sweep for still-open CRITICAL alerts older than the escalation window and re-page them.
     * Interval is configurable ({@code gmepay.ops.paging.escalation.sweep-ms}, default 60s).
     */
    @Scheduled(fixedDelayString = "${gmepay.ops.paging.escalation.sweep-ms:60000}")
    public void sweep() {
        Instant cutoff = Instant.now().minus(escalateAfter);
        List<OpsAlertView> criticals = store.recent("CRITICAL", null, 0);
        for (OpsAlertView a : criticals) {
            if (a.acked()) {
                continue; // acknowledged ⇒ escalation stops
            }
            if (occurredBefore(a, cutoff)) {
                log.info("escalating un-acked CRITICAL alert seq={} type={} subjectRef={}",
                        a.seq(), a.alertType(), a.subjectRef());
                dispatcher.escalate(a);
            }
        }
    }

    /** True when the alert's occurredAt parses to an instant older than the cutoff. */
    private static boolean occurredBefore(OpsAlertView a, Instant cutoff) {
        try {
            return Instant.parse(a.occurredAt()).isBefore(cutoff);
        } catch (RuntimeException e) {
            // Non-ISO occurredAt (some producers embed non-instant strings): fall back to
            // the last paging attempt time, else treat as escalatable.
            if (a.paging() != null && a.paging().lastAt() != null) {
                try {
                    return Instant.parse(a.paging().lastAt())
                            .isBefore(cutoff.truncatedTo(ChronoUnit.SECONDS));
                } catch (RuntimeException ignored) {
                    return true;
                }
            }
            return true;
        }
    }
}
