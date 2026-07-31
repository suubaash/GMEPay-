package com.gme.pay.notify.alert;

import com.gme.pay.notify.domain.WebhookPayloads;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Phase-1 operational alerting for the webhook pipeline (WBS 8.6-T24).
 *
 * <p>Two P2 alert conditions per spec:
 * <ul>
 *   <li><b>WEBHOOK_DLQ</b> — a delivery exhausted the retry policy and was promoted to
 *       the DLQ. Always recorded (every DLQ promotion is signal).</li>
 *   <li><b>WEBHOOK_QUEUE_DEPTH</b> — the pending-delivery backlog exceeds
 *       {@link #QUEUE_DEPTH_THRESHOLD}. Recorded only when no unacknowledged
 *       queue-depth alert was fired within {@link #DEDUP_WINDOW} (alert-storm
 *       suppression).</li>
 * </ul>
 *
 * <p>Phase-1 transport is the durable {@code alert_event} table; future phases swap
 * for PagerDuty/Slack behind this same method surface. Never throws to the caller —
 * an alerting failure must not break webhook delivery — so persistence errors are
 * logged and swallowed.
 */
@Service
public class WebhookAlertService {

    /** Strictly-greater-than threshold for the queue-depth breach alert. */
    public static final long QUEUE_DEPTH_THRESHOLD = 500L;

    /** Suppression window for repeat queue-depth alerts (same partner). */
    public static final Duration DEDUP_WINDOW = Duration.ofMinutes(10);

    /** Spec severity for both webhook alert types. */
    public static final String SEVERITY_P2 = "P2";

    public static final String TYPE_DLQ = "WEBHOOK_DLQ";
    public static final String TYPE_QUEUE_DEPTH = "WEBHOOK_QUEUE_DEPTH";

    /**
     * A partner endpoint's circuit breaker opened: its deliveries are being skipped so the shared
     * workers stay available to other partners (T3-11 defect 5, per-endpoint fairness).
     */
    public static final String TYPE_ENDPOINT_CIRCUIT_OPEN = "WEBHOOK_ENDPOINT_CIRCUIT_OPEN";

    /** Sentinel partner id for a global (non-partner-attributable) queue-depth breach. */
    static final long GLOBAL_PARTNER_ID = 0L;

    private static final Logger log = LoggerFactory.getLogger(WebhookAlertService.class);

    private final AlertEventRepository alertRepository;
    private final Clock clock;

    public WebhookAlertService(AlertEventRepository alertRepository, Clock clock) {
        this.alertRepository = Objects.requireNonNull(alertRepository);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Payload-aware DLQ alert: extracts {@code partnerId} from the delivery payload
     * (same flat / nested-envelope shapes the dispatcher accepts) and fires the DLQ
     * alert. Convenience entry point for the persistence layer, which holds the payload
     * but not a parsed partner id.
     */
    @Transactional
    public Optional<AlertEventEntity> fireDlqAlertForPayload(String payload, Long originalId, String eventType) {
        return fireDlqAlert(extractPartnerId(payload), originalId, eventType);
    }

    /**
     * Reads a numeric {@code partnerId} from the event payload; null if absent/non-numeric.
     *
     * <p>Delegates to {@link WebhookPayloads}: the same parse also decides which partner's fair share
     * a row is selected under and which endpoint it is signed for, and three copies of that rule is how
     * one of them ends up disagreeing. Kept as a method here because it is the alert path's entry point.
     */
    Long extractPartnerId(String payload) {
        return WebhookPayloads.partnerId(payload);
    }

    /**
     * Records a P2 DLQ-promotion alert. Always inserts (no dedup) — each exhausted
     * delivery is independently actionable.
     *
     * @param partnerId    partner the delivery belonged to; may be {@code null} if unknown
     * @param originalId   {@code webhook_delivery_log.id} that exhausted its retries
     * @param eventType    domain event type, e.g. {@code payment.approved}
     * @return the persisted alert, or empty if persistence failed (already logged)
     */
    @Transactional
    public Optional<AlertEventEntity> fireDlqAlert(Long partnerId, Long originalId, String eventType) {
        String message = "Webhook delivery promoted to DLQ after exhausting retries"
                + " (deliveryId=" + originalId + ", eventType=" + eventType + ")";
        String context = "{\"originalId\":" + originalId
                + ",\"eventType\":" + jsonString(eventType) + "}";
        try {
            return Optional.of(insert(TYPE_DLQ, partnerId, message, context));
        } catch (RuntimeException e) {
            log.error("failed to record WEBHOOK_DLQ alert (deliveryId={}): {}", originalId, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Records a P2 queue-depth-breach alert when {@code pendingCount} strictly exceeds
     * {@link #QUEUE_DEPTH_THRESHOLD}, unless an unacknowledged queue-depth alert for the
     * same partner was already fired within {@link #DEDUP_WINDOW}.
     *
     * @param partnerId    partner scope; pass {@code null} for a global breach (uses sentinel id)
     * @param pendingCount current count of PENDING delivery rows
     * @return the persisted alert, or empty when below threshold or suppressed by dedup
     */
    @Transactional
    public Optional<AlertEventEntity> fireQueueDepthAlert(Long partnerId, long pendingCount) {
        if (pendingCount <= QUEUE_DEPTH_THRESHOLD) {
            return Optional.empty();
        }
        long scopeId = partnerId == null ? GLOBAL_PARTNER_ID : partnerId;
        Instant cutoff = Instant.now(clock).minus(DEDUP_WINDOW);
        try {
            if (alertRepository.existsByPartnerIdAndAlertTypeAndAcknowledgedAtIsNullAndFiredAtAfter(
                    scopeId, TYPE_QUEUE_DEPTH, cutoff)) {
                log.debug("queue-depth alert suppressed (recent unacknowledged): partner={} pending={}",
                        scopeId, pendingCount);
                return Optional.empty();
            }
            String message = "Webhook pending-delivery backlog breached threshold: "
                    + pendingCount + " > " + QUEUE_DEPTH_THRESHOLD;
            String context = "{\"pendingCount\":" + pendingCount
                    + ",\"threshold\":" + QUEUE_DEPTH_THRESHOLD + "}";
            return Optional.of(insert(TYPE_QUEUE_DEPTH, scopeId, message, context));
        } catch (RuntimeException e) {
            log.error("failed to record WEBHOOK_QUEUE_DEPTH alert (pending={}): {}",
                    pendingCount, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Records a P2 alert that one partner endpoint has been short-circuited, deduped per partner over
     * {@link #DEDUP_WINDOW} exactly like the queue-depth alert.
     *
     * <p>This exists because the fairness fix <b>deliberately stops attempting deliveries</b> to a
     * failing endpoint. Skipping work quietly would trade one invisible failure (everyone stalled
     * behind one dead partner) for another (one partner silently not being delivered to), so the
     * suppression has to announce itself. The row's own attempt counter is untouched while the breaker
     * is open, so this alert is the only immediate signal that the endpoint is being skipped — the DLQ
     * and backlog alerts follow later if it stays down.
     *
     * @param partnerId          the endpoint's partner; {@code null} for the unattributed group
     * @param consecutiveFailures how many failures in a row tripped it
     * @param openFor            how long deliveries will be skipped before a half-open probe
     * @return the persisted alert, or empty when suppressed by dedup or when persistence failed
     */
    @Transactional
    public Optional<AlertEventEntity> fireEndpointCircuitOpenAlert(Long partnerId,
                                                                   int consecutiveFailures,
                                                                   Duration openFor) {
        long scopeId = partnerId == null ? GLOBAL_PARTNER_ID : partnerId;
        Instant cutoff = Instant.now(clock).minus(DEDUP_WINDOW);
        try {
            if (alertRepository.existsByPartnerIdAndAlertTypeAndAcknowledgedAtIsNullAndFiredAtAfter(
                    scopeId, TYPE_ENDPOINT_CIRCUIT_OPEN, cutoff)) {
                log.debug("endpoint-circuit alert suppressed (recent unacknowledged): partner={}",
                        scopeId);
                return Optional.empty();
            }
            String message = "Webhook endpoint circuit opened for partner " + scopeId + " after "
                    + consecutiveFailures + " consecutive failed deliveries; its deliveries are"
                    + " skipped for " + openFor + " so other partners keep the shared workers."
                    + " Rows stay PENDING with their attempt counters untouched.";
            String context = "{\"consecutiveFailures\":" + consecutiveFailures
                    + ",\"openForSeconds\":" + openFor.toSeconds() + "}";
            return Optional.of(insert(TYPE_ENDPOINT_CIRCUIT_OPEN, scopeId, message, context));
        } catch (RuntimeException e) {
            log.error("failed to record WEBHOOK_ENDPOINT_CIRCUIT_OPEN alert (partner={}): {}",
                    scopeId, e.getMessage(), e);
            return Optional.empty();
        }
    }

    private AlertEventEntity insert(String alertType, Long partnerId, String message, String context) {
        AlertEventEntity row = new AlertEventEntity();
        row.setAlertType(alertType);
        row.setSeverity(SEVERITY_P2);
        row.setPartnerId(partnerId);
        row.setMessage(message);
        row.setContext(context);
        row.setFiredAt(Instant.now(clock));
        return alertRepository.save(row);
    }

    private static String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
