package com.gme.pay.payment.persistence;

import com.gme.pay.contracts.events.OpsAlertPayload;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Durable, queryable archive of the ops alerts this service emits (table {@code ops_alerts}, Flyway
 * V006 — gap <b>T3-3</b> / COO#4 "alerting terminates in a 200-entry in-memory deque").
 *
 * <p>Follows the same shape as {@link RevenuePostingFailureStore}, the service's existing
 * "durable record of a thing that used to be only a log line" pattern: a thin {@code @Service} over a
 * Spring Data repository whose write path <b>never throws</b>.
 *
 * <h2>Never throws</h2>
 * <p>{@link #record} sits on the payment path (the decline monitor is called per authorization
 * outcome). A database hiccup while archiving an alert must not turn a payment into an error, so a
 * failure to persist is logged at ERROR — the last-resort signal — and swallowed. The caller
 * ({@code OpsAlertPipeline}) then publishes and notifies anyway, so a DB outage degrades durability,
 * not alerting.
 *
 * <h2>Bounded reads + retention</h2>
 * <p>{@link #recent} always applies a hard cap ({@value #MAX_LIMIT}), so no caller can ask for an
 * unbounded history. {@link #prune} drops rows older than
 * {@code gmepay.ops.alerts.retention-days} (default 90) and is driven by
 * {@code OpsAlertRetentionScheduler}.
 */
@Service
public class OpsAlertArchive {

    /** Hard ceiling on any single query, whatever the caller asks for. */
    public static final int MAX_LIMIT = 500;

    /** Applied when the caller passes a non-positive limit. */
    public static final int DEFAULT_LIMIT = 50;

    private static final Logger log = LoggerFactory.getLogger(OpsAlertArchive.class);

    private final OpsAlertRepository repository;
    private final Clock clock;
    private final Duration retention;

    public OpsAlertArchive(OpsAlertRepository repository,
                           Clock clock,
                           @Value("${gmepay.ops.alerts.retention-days:90}") int retentionDays) {
        this.repository = repository;
        this.clock = clock;
        this.retention = Duration.ofDays(retentionDays > 0 ? retentionDays : 90);
    }

    /**
     * Persist one emitted alert. Returns the row id, or {@code null} when the write failed (in which
     * case the alert is still published and notified — see class javadoc).
     */
    public Long record(OpsAlertPayload alert) {
        try {
            Instant now = Instant.now(clock);
            OpsAlertEntity saved = repository.save(new OpsAlertEntity(
                    alert.alertType(),
                    alert.severity(),
                    subjectOrGlobal(alert.subjectRef()),
                    alert.detail(),
                    parseOccurredAt(alert.occurredAt(), now),
                    now));
            return saved.getId();
        } catch (RuntimeException e) {
            log.error("FAILED to persist ops alert {} for subject={} — this alert now survives only in"
                            + " logs, which is exactly what ops_alerts exists to prevent: {}",
                    alert.alertType(), alert.subjectRef(), e.toString());
            return null;
        }
    }

    /**
     * Stamp the notification outcome onto a previously {@link #record}ed row. Never throws; a lost
     * stamp leaves the row {@code PENDING}, which is strictly better than losing the alert.
     */
    public void recordNotification(Long id, String status, String channel, String error) {
        if (id == null) {
            return;
        }
        try {
            repository.findById(id).ifPresent(row -> {
                row.recordNotification(status, channel, error);
                repository.save(row);
            });
        } catch (RuntimeException e) {
            log.error("failed to stamp notification outcome {} on ops_alerts row {}: {}",
                    status, id, e.toString());
        }
    }

    /**
     * Recent alerts, newest-first, optionally narrowed by severity / alertType. The limit is clamped
     * to {@code [1, }{@value #MAX_LIMIT}{@code ]}.
     */
    @Transactional(readOnly = true)
    public List<OpsAlertEntity> recent(String severity, String alertType, int limit) {
        int capped = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        return repository.findRecent(blankToNull(severity), blankToNull(alertType),
                PageRequest.of(0, capped));
    }

    /** Drop alerts older than the configured retention window. Returns rows deleted. */
    @Transactional
    public int prune() {
        Instant cutoff = Instant.now(clock).minus(retention);
        int deleted = repository.deleteOlderThan(cutoff);
        if (deleted > 0) {
            log.info("pruned {} ops_alerts rows older than {} ({} day retention)",
                    deleted, cutoff, retention.toDays());
        }
        return deleted;
    }

    private static String subjectOrGlobal(String subjectRef) {
        return (subjectRef == null || subjectRef.isBlank()) ? "global" : subjectRef;
    }

    private static Instant parseOccurredAt(String iso, Instant fallback) {
        if (iso == null || iso.isBlank()) {
            return fallback;
        }
        try {
            return Instant.from(DateTimeFormatter.ISO_INSTANT.parse(iso));
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
