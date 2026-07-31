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
 * This class was once marked "single-replica-only", with a note to ShedLock-guard it if the BFF ever
 * gained a DataSource. <b>The BFF has now gained one</b> (the {@code ops_alerts} table, V001) — and
 * the sweep is still deliberately unlocked. The note was reversed before that store existed, and the
 * store's arrival does not restore it. Two reasons, in order:
 *
 * <ol>
 *   <li><b>A lock can only ever subtract escalations.</b> If the lock row sticks, the lock provider is
 *       unavailable, or {@code lockAtMostFor} is longer than an incident, then <em>no</em> replica
 *       sweeps and an un-acked CRITICAL alert stops escalating. The failure of a lock would be a
 *       <b>missed page</b>, which is the exact failure this mechanism exists to prevent. A missed page
 *       is not recoverable; a duplicate one is an annoyance.</li>
 *   <li><b>The duplicate a lock would prevent is already prevented, in the right place.</b> Duplicate
 *       paging is stopped at the pager by the shared {@link PagingCooldown}, which the dispatcher
 *       <em>claims atomically before</em> each page. Every replica may decide to escalate; at most one
 *       page goes out per dedupe window.</li>
 * </ol>
 *
 * <p>Historically the argument was stronger still: {@code OpsAlertStore} was a per-JVM buffer, so each
 * replica held a <em>different</em> set of alerts and a lock would have meant every other replica's
 * un-acked CRITICALs were never escalated <em>at all</em>. That specific hazard is gone — the store is
 * shared now, so any single replica can see every alert — but "the lock is the new single point of
 * silence" survives it, and the retention sweeper next door shows where a lock <em>is</em> the right
 * call. See {@code OpsSchedulingConfig} and Flyway V003 for the contrast, stated once in each place.
 *
 * <p>What the shared store did fix is the limitation this javadoc used to end on: the alerts list, the
 * alert ids and the ack state are no longer per-replica, so an alert acked on one replica now stops
 * escalating on all of them. See {@link OpsAlertStore}.
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
        log.info("ops paging escalation ENABLED (after={}). Runs on EVERY replica by design and is "
                        + "deliberately NOT ShedLocked: a lock could only subtract escalations, so a "
                        + "stuck lock or an unavailable lock provider would silence the pager during "
                        + "an incident. Duplicate pages are prevented at the pager by the shared "
                        + "PagingCooldown, not by pinning the sweep.",
                this.escalateAfter);
    }

    /**
     * Sweep for still-open CRITICAL alerts older than the escalation window and re-page them.
     * Interval is configurable ({@code gmepay.ops.paging.escalation.sweep-ms}, default 60s).
     *
     * <p>Deliberately NOT {@code @SchedulerLock}ed — see the class javadoc. A {@code LockProvider}
     * exists in this context (the retention sweeper uses one), so the absence here is a decision, not
     * a missing capability.
     *
     * <p>The CRITICAL query is explicitly capped at {@link OpsAlertStore#MAX_LIMIT} rather than passing
     * the old "0 = unlimited": against a table an unbounded fetch would pull the whole retention window
     * into the heap every tick. 500 still-open CRITICAL alerts is itself a catastrophe, and the newest
     * are swept first.
     */
    @Scheduled(fixedDelayString = "${gmepay.ops.paging.escalation.sweep-ms:60000}")
    public void sweep() {
        Instant cutoff = Instant.now().minus(escalateAfter);
        List<OpsAlertView> criticals = store.recent("CRITICAL", null, OpsAlertStore.MAX_LIMIT);
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
