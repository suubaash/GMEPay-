package com.gme.pay.txn.service;

import com.gme.pay.events.EventPublisher;
import com.gme.pay.txn.domain.model.Transaction;
import com.gme.pay.txn.domain.model.TransactionStatus;
import com.gme.pay.txn.outbox.OpsAlertEvent;
import com.gme.pay.txn.outbox.OutboxAppender;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Ops stuck/aged-transaction alert sweep.
 *
 * <p>A {@code @Scheduled} job (config-gated, <b>default OFF</b>) that finds transactions stuck in a
 * non-terminal state longer than a configurable threshold and emits an
 * {@link com.gme.pay.contracts.events.OpsAlertPayload ops.alert} via the existing outbox
 * {@link EventPublisher} seam (topic {@code gmepay.ops.alert}); the {@code LoggingEventPublisher}
 * logs it when no broker is configured.
 *
 * <p>Swept states (configurable via {@code gmepay.txn.stuck-alert.statuses}, default
 * {@code UNCERTAIN}): {@code UNCERTAIN} is the primary target (scheme-timeout rows awaiting
 * reconciliation / operator force-resolve); {@code PENDING_DEBIT} / {@code SCHEME_SENT} can be
 * added to catch in-flight rows that never resolved.
 *
 * <p>Alert classification:
 * <ul>
 *   <li>{@code alertType} — {@code UNCERTAIN_AGED} for UNCERTAIN rows, else {@code STUCK_TXN}.</li>
 *   <li>{@code severity}  — {@code WARN} once aged past the threshold; {@code CRITICAL} once aged
 *       past {@code criticalMultiplier}× the threshold.</li>
 *   <li>{@code subjectRef} — the {@code txnRef}.</li>
 * </ul>
 *
 * <p>Exception-safe: an error emitting one alert is logged and skipped so a single bad row cannot
 * abort the sweep batch. This sweep only READS transactions — it never mutates state (recovery is
 * the operator's job via the force-resolve endpoint).
 */
@Component
public class StuckTransactionAlertSweeper {

    private static final Logger log = LoggerFactory.getLogger(StuckTransactionAlertSweeper.class);

    static final String ALERT_UNCERTAIN_AGED = "UNCERTAIN_AGED";
    static final String ALERT_STUCK_TXN = "STUCK_TXN";
    static final String SEVERITY_WARN = "WARN";
    static final String SEVERITY_CRITICAL = "CRITICAL";

    private final TransactionRepository repository;
    private final EventPublisher eventPublisher;
    private final Clock clock;
    private final boolean enabled;
    private final long thresholdSeconds;
    private final long criticalMultiplier;
    private final List<String> statuses;

    /** Primary constructor used by Spring (system UTC clock). */
    @Autowired
    public StuckTransactionAlertSweeper(
            TransactionRepository repository,
            @Qualifier(OutboxAppender.BEAN_NAME) EventPublisher eventPublisher,
            @Value("${gmepay.txn.stuck-alert.enabled:false}") boolean enabled,
            @Value("${gmepay.txn.stuck-alert.threshold-seconds:900}") long thresholdSeconds,
            @Value("${gmepay.txn.stuck-alert.critical-multiplier:4}") long criticalMultiplier,
            @Value("${gmepay.txn.stuck-alert.statuses:UNCERTAIN}") List<String> statuses) {
        this(repository, eventPublisher, Clock.systemUTC(),
                enabled, thresholdSeconds, criticalMultiplier, statuses);
    }

    /** Clock-injectable constructor for deterministic unit tests. */
    public StuckTransactionAlertSweeper(
            TransactionRepository repository,
            EventPublisher eventPublisher,
            Clock clock,
            boolean enabled,
            long thresholdSeconds,
            long criticalMultiplier,
            List<String> statuses) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.enabled = enabled;
        this.thresholdSeconds = thresholdSeconds;
        this.criticalMultiplier = criticalMultiplier <= 0 ? 1 : criticalMultiplier;
        this.statuses = statuses == null || statuses.isEmpty()
                ? List.of(TransactionStatus.UNCERTAIN.name())
                : statuses;
    }

    /**
     * Scheduled sweep — runs on a fixed delay (default 60 s), KST zone. Config-gated: a no-op when
     * {@code gmepay.txn.stuck-alert.enabled} is false (the default).
     */
    @Scheduled(fixedDelayString = "${gmepay.txn.stuck-alert.interval-ms:60000}",
               zone = "Asia/Seoul")
    @SchedulerLock(name = "StuckTransactionAlertSweeper_sweep",
                   lockAtMostFor = "PT5M", lockAtLeastFor = "PT5S")
    public void sweep() {
        if (!enabled) {
            return;
        }
        emitAlerts();
    }

    /**
     * Runs one sweep pass: finds stuck rows and publishes an ops.alert for each. Package-visible and
     * returns the alerts emitted so unit tests can invoke it deterministically. Runs in a
     * transaction so the outbox-appending publisher writes inside a persistence context.
     */
    @Transactional
    public List<OpsAlertEvent> emitAlerts() {
        Instant now = clock.instant();
        Instant stuckBefore = now.minusSeconds(thresholdSeconds);
        Instant criticalBefore = now.minusSeconds(thresholdSeconds * criticalMultiplier);

        List<Transaction> stuck;
        try {
            stuck = repository.findStuck(stuckBefore, statuses);
        } catch (Exception ex) {
            log.error("[StuckTxnAlertSweeper] failed to query stuck transactions", ex);
            return List.of();
        }
        if (stuck.isEmpty()) {
            return List.of();
        }

        log.info("[StuckTxnAlertSweeper] found {} stuck transaction(s) (threshold={}s), emitting ops.alerts",
                stuck.size(), thresholdSeconds);

        List<OpsAlertEvent> emitted = new ArrayList<>(stuck.size());
        for (Transaction txn : stuck) {
            try {
                OpsAlertEvent alert = buildAlert(txn, now, criticalBefore);
                eventPublisher.publish(alert);
                emitted.add(alert);
            } catch (Exception ex) {
                log.error("[StuckTxnAlertSweeper] failed to emit alert for txn {} — skipping",
                        txn.txnRef(), ex);
            }
        }
        return emitted;
    }

    /** Classifies a stuck row into an {@link OpsAlertEvent} (alertType + severity + detail). */
    private OpsAlertEvent buildAlert(Transaction txn, Instant now, Instant criticalBefore) {
        boolean uncertain = txn.status() == TransactionStatus.UNCERTAIN;
        String alertType = uncertain ? ALERT_UNCERTAIN_AGED : ALERT_STUCK_TXN;
        // Aged relative to the last state change (updatedAt); fall back to createdAt.
        Instant since = txn.updatedAt() != null ? txn.updatedAt() : txn.createdAt();
        String severity = since != null && since.isBefore(criticalBefore)
                ? SEVERITY_CRITICAL : SEVERITY_WARN;
        long ageSeconds = since != null ? Duration.between(since, now).getSeconds() : -1;
        String detail = "Transaction " + txn.txnRef() + " stuck in " + txn.status()
                + " for " + ageSeconds + "s (threshold " + thresholdSeconds + "s)"
                + (txn.partnerId() != null ? ", partnerId=" + txn.partnerId() : "");
        return OpsAlertEvent.of(alertType, severity, txn.txnRef(), detail);
    }
}
