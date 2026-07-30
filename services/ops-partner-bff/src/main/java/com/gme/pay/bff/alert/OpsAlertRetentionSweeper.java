package com.gme.pay.bff.alert;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the durable {@code ops_alerts} table bounded: deletes alerts written more than
 * {@code gmepay.ops.alerts.retention-days} days ago (default 90) every
 * {@code gmepay.ops.alerts.prune-interval-ms} (default 6h).
 *
 * <p><b>Why this exists at all.</b> The store this replaces was bounded by <em>count</em> (a
 * 200-entry deque), which is why a busy hour silently evicted the morning's evidence. A table is
 * bounded by nothing unless something prunes it, so "durable" without this class just means
 * "unbounded". Time-bounded rather than count-bounded is the right trade here for the same reason as
 * payment-executor's emitter-side pruner: alert volume is inherently low (every monitor has a
 * per-subject cooldown), so a window long enough for an audit still never grows without limit.
 *
 * <p>Set {@code gmepay.ops.alerts.prune-enabled=false} to keep alerts forever — same switch and same
 * default (on) as payment-executor.
 *
 * <p><b>Locked, unlike the escalation sweep.</b> The delete is idempotent and would survive running
 * twice; it is locked anyway per T3-11 (N replicas issuing the same bulk delete is N times the
 * contention for no benefit, and "is this one locked?" must not be re-decided per job). The
 * escalation sweep is the deliberate opposite — see {@code OpsPagingEscalationScheduler} — because
 * there a lock could only ever silence a page.
 *
 * <p>{@code @ConditionalOnBean(JpaOpsAlertStore.class)}: with {@code gmepay.ops.alerts.store=memory}
 * there is no table to prune (the deque evicts itself), so no sweeper is registered.
 */
@Component
@ConditionalOnBean(JpaOpsAlertStore.class)
@ConditionalOnProperty(name = "gmepay.ops.alerts.prune-enabled", havingValue = "true",
        matchIfMissing = true)
public class OpsAlertRetentionSweeper {

    private static final Logger log = LoggerFactory.getLogger(OpsAlertRetentionSweeper.class);

    private final JpaOpsAlertStore store;

    public OpsAlertRetentionSweeper(JpaOpsAlertStore store) {
        this.store = store;
        log.info("ops_alerts retention sweeper armed ({}-day window). This is an ENGINEERING "
                        + "default: a records-retention owner should confirm how long operational "
                        + "alert evidence and the operator acks on it must be kept.",
                store.retention().toDays());
    }

    @Scheduled(fixedDelayString = "${gmepay.ops.alerts.prune-interval-ms:21600000}",
            initialDelayString = "${gmepay.ops.alerts.prune-initial-delay-ms:300000}")
    @SchedulerLock(name = "OpsAlertRetentionSweeper_prune",
            lockAtMostFor = "PT30M", lockAtLeastFor = "PT0S")
    public void prune() {
        try {
            store.prune();
        } catch (RuntimeException e) {
            // A retention failure must never take the scheduler down (it shares the pool with
            // nothing else here, but the escalation sweep may join it): the table just grows until
            // someone fixes the cause.
            log.error("ops_alerts retention prune failed: {}", e.toString());
        }
    }
}
