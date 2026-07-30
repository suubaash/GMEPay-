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
 * <h2>Replica safety — and why this sweep must NOT be locked to one replica</h2>
 * This class used to be marked "single-replica-only", with a note to ShedLock-guard it if the BFF
 * ever gained a DataSource. <b>That would be the wrong fix, and shipping it would create a worse
 * bug than the one it closed.</b>
 *
 * <p>{@link OpsAlertStore} is a per-JVM rolling buffer, so each replica holds a <em>different</em>
 * set of alerts — whichever ones its own Kafka consumer received. A distributed lock lets exactly
 * one replica sweep, which means the alerts held by every <em>other</em> replica would never be
 * escalated at all. Un-acked CRITICAL alerts silently stop escalating: a missed page, which is the
 * failure this whole mechanism exists to prevent.
 *
 * <p>So the sweep runs on <b>every</b> replica, over its own buffer, and duplicate paging is
 * prevented where it should be — at the pager, by the shared {@link PagingCooldown} the dispatcher
 * claims atomically before each page. Every replica may decide to escalate; at most one succeeds
 * per dedupe window.
 *
 * <p>The remaining honest limitation is not in this class: the alert buffer itself is per-replica,
 * so the alerts <em>list</em> and its ack state differ between replicas. See {@link OpsAlertStore}.
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
        log.info("ops paging escalation ENABLED (after={}). Runs on EVERY replica by design — the "
                        + "alert buffer is per-replica, so locking the sweep would stop escalating "
                        + "the alerts held elsewhere. Duplicate pages are prevented by the shared "
                        + "PagingCooldown, not by pinning the sweep.",
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
