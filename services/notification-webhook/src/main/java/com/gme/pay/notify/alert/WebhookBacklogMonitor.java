package com.gme.pay.notify.alert;

import com.gme.pay.contracts.events.OpsAlertPayload;
import com.gme.pay.events.EventPublisher;
import com.gme.pay.notify.persistence.WebhookDeliveryRepository;
import com.gme.pay.notify.persistence.WebhookPersistenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * Ops backlog monitor (Ops wave) — a config-gated scheduled check that emits a
 * {@code WEBHOOK_BACKLOG} {@link OpsAlertPayload} when the webhook delivery backlog
 * grows too large.
 *
 * <p><b>Backlog</b> = overdue PENDING rows (created before {@code now - overdueWindow},
 * so a healthy in-flight burst is excluded) + all DLQ rows. When that count strictly
 * exceeds the configured threshold, an alert is published on the
 * {@link EventPublisher} seam (topic {@code gmepay.ops.alert}); severity scales with
 * how far over the threshold the backlog is. With no broker wired, the seam's
 * {@code LogEventPublisher} fallback logs the alert instead.
 *
 * <p><b>Default off.</b> Enable with {@code gmepay.webhook.backlog-monitor.enabled=true}.
 * Distinct from the per-drain {@code WEBHOOK_QUEUE_DEPTH} alert (durable ledger); this
 * is the cross-cutting Ops-wave signal on the shared {@code ops.alert} topic.
 */
@Component
@ConditionalOnProperty(name = "gmepay.webhook.backlog-monitor.enabled", havingValue = "true")
public class WebhookBacklogMonitor {

    private static final Logger log = LoggerFactory.getLogger(WebhookBacklogMonitor.class);

    public static final String ALERT_TYPE = "WEBHOOK_BACKLOG";

    // OpsAlertPayload severities.
    static final String SEV_INFO = "INFO";
    static final String SEV_WARN = "WARN";
    static final String SEV_CRITICAL = "CRITICAL";

    private final WebhookDeliveryRepository deliveryRepository;
    private final EventPublisher eventPublisher;
    private final Clock clock;
    private final long threshold;
    private final Duration overdueWindow;

    public WebhookBacklogMonitor(WebhookDeliveryRepository deliveryRepository,
                                 EventPublisher eventPublisher,
                                 Clock clock,
                                 @Value("${gmepay.webhook.backlog-monitor.threshold:100}") long threshold,
                                 @Value("${gmepay.webhook.backlog-monitor.overdue-window-seconds:300}")
                                 long overdueWindowSeconds) {
        this.deliveryRepository = Objects.requireNonNull(deliveryRepository);
        this.eventPublisher = Objects.requireNonNull(eventPublisher);
        this.clock = Objects.requireNonNull(clock);
        this.threshold = threshold > 0 ? threshold : 100L;
        this.overdueWindow = Duration.ofSeconds(overdueWindowSeconds > 0 ? overdueWindowSeconds : 300L);
    }

    /**
     * Scheduled backlog check. Default cadence 60s
     * ({@code gmepay.webhook.backlog-monitor.interval-ms}); first run after a short delay.
     */
    @Scheduled(
            fixedDelayString = "${gmepay.webhook.backlog-monitor.interval-ms:60000}",
            initialDelayString = "${gmepay.webhook.backlog-monitor.initial-delay-ms:10000}")
    public void checkBacklog() {
        evaluate();
    }

    /**
     * Evaluates the backlog once and publishes an alert if over threshold. Package-private
     * so tests can drive a single evaluation without the scheduler. Returns the published
     * payload, or {@code null} if under/at threshold (nothing emitted).
     */
    OpsAlertPayload evaluate() {
        Instant now = Instant.now(clock);
        long overduePending = deliveryRepository.countByStatusAndCreatedAtBefore(
                WebhookPersistenceService.STATUS_PENDING, now.minus(overdueWindow));
        long dlq = deliveryRepository.countByStatus(WebhookPersistenceService.STATUS_DLQ);
        long backlog = overduePending + dlq;

        if (backlog <= threshold) {
            log.debug("webhook backlog {} within threshold {} (overduePending={}, dlq={})",
                    backlog, threshold, overduePending, dlq);
            return null;
        }

        String severity = severityFor(backlog);
        String detail = "backlog=" + backlog + " (overduePending=" + overduePending
                + ", dlq=" + dlq + ") > threshold=" + threshold;
        OpsAlertPayload alert = new OpsAlertPayload(
                OpsAlertPayload.EVENT_TYPE,
                ALERT_TYPE,
                severity,
                "global",
                detail,
                DateTimeFormatter.ISO_INSTANT.format(now));
        try {
            eventPublisher.publish(new OpsAlertEvent(alert));
            log.info("published WEBHOOK_BACKLOG ops alert: severity={} {}", severity, detail);
        } catch (RuntimeException e) {
            // Alerting must never break the service; the broker publisher may throw.
            log.error("failed to publish WEBHOOK_BACKLOG ops alert ({}): {}", detail, e.getMessage(), e);
        }
        return alert;
    }

    /**
     * Severity by how far over threshold the backlog sits: INFO just over, WARN &gt;=2x,
     * CRITICAL &gt;=5x.
     */
    private String severityFor(long backlog) {
        if (backlog >= threshold * 5) {
            return SEV_CRITICAL;
        }
        if (backlog >= threshold * 2) {
            return SEV_WARN;
        }
        return SEV_INFO;
    }
}
