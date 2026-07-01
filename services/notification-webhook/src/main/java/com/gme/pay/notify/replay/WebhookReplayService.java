package com.gme.pay.notify.replay;

import com.gme.pay.notify.persistence.WebhookDeliveryEntity;
import com.gme.pay.notify.persistence.WebhookDeliveryRepository;
import com.gme.pay.notify.persistence.WebhookPersistenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Operator-facing webhook replay (Ops wave).
 *
 * <p>Re-enqueues a delivery row — typically one that reached {@code DLQ} or
 * {@code FAILED} — back to {@code PENDING} so the existing {@code WebhookDispatcher}
 * drain re-sends it on its next cycle. This service does <b>not</b> dispatch; it
 * reuses the dispatcher's own drain machinery by simply flipping the row's status
 * (and resetting its backoff clock) so the next drain picks it up.
 *
 * <p><b>Idempotent-safe.</b> If the delivery is already live — {@code PENDING}
 * (in-flight) or already {@code DELIVERED} — the replay is a no-op that returns a
 * {@code NOOP_*} outcome rather than duplicating an in-flight delivery. Only a
 * terminally-parked row ({@code DLQ}/{@code FAILED}) is re-enqueued.
 *
 * <p>Every request — accepted or no-op — records a {@link WebhookReplayAuditEntity}
 * capturing who asked and why.
 */
@Service
public class WebhookReplayService {

    private static final Logger log = LoggerFactory.getLogger(WebhookReplayService.class);

    /** Outcome codes (also written to the audit row). */
    public enum Outcome {
        /** Row was DLQ/FAILED and has been re-enqueued to PENDING. */
        REENQUEUED,
        /** Row was already PENDING (in-flight) — not re-enqueued. */
        NOOP_ALREADY_PENDING,
        /** Row was already DELIVERED — not re-enqueued. */
        NOOP_ALREADY_DELIVERED,
        /** No delivery row matched the id/reference. */
        NOT_FOUND
    }

    /** Result of a replay attempt: the outcome plus the delivery id when known. */
    public record ReplayResult(Outcome outcome, Long deliveryId) {
        public boolean reenqueued() {
            return outcome == Outcome.REENQUEUED;
        }
    }

    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookReplayAuditRepository auditRepository;
    private final Clock clock;

    public WebhookReplayService(WebhookDeliveryRepository deliveryRepository,
                                WebhookReplayAuditRepository auditRepository,
                                Clock clock) {
        this.deliveryRepository = Objects.requireNonNull(deliveryRepository);
        this.auditRepository = Objects.requireNonNull(auditRepository);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Replays a delivery by its {@code webhook_delivery_log.id}.
     *
     * @param deliveryId  the delivery-log row id
     * @param requestedBy operator identity (audit); defaults to {@code "unknown"} if blank
     * @param reason      free-text justification (audit); may be {@code null}
     */
    @Transactional
    public ReplayResult replayById(Long deliveryId, String requestedBy, String reason) {
        Objects.requireNonNull(deliveryId, "deliveryId must not be null");
        Optional<WebhookDeliveryEntity> found = deliveryRepository.findById(deliveryId);
        if (found.isEmpty()) {
            audit(deliveryId, null, requestedBy, reason, Outcome.NOT_FOUND);
            return new ReplayResult(Outcome.NOT_FOUND, deliveryId);
        }
        return reenqueue(found.get(), requestedBy, reason);
    }

    /**
     * Replays the (single) delivery identified by a logical reference
     * ({@code webhook_id}). If multiple rows share the reference, the terminally-parked
     * one ({@code DLQ}/{@code FAILED}) is preferred; otherwise the first is used so a
     * live row still yields an idempotent no-op.
     *
     * @param reference   the logical webhook id (the event's aggregate id)
     * @param requestedBy operator identity (audit)
     * @param reason      free-text justification (audit)
     */
    @Transactional
    public ReplayResult replayByReference(String reference, String requestedBy, String reason) {
        Objects.requireNonNull(reference, "reference must not be null");
        List<WebhookDeliveryEntity> rows = deliveryRepository.findByWebhookId(reference);
        if (rows.isEmpty()) {
            audit(null, reference, requestedBy, reason, Outcome.NOT_FOUND);
            return new ReplayResult(Outcome.NOT_FOUND, null);
        }
        WebhookDeliveryEntity target = rows.stream()
                .filter(WebhookReplayService::isReplayable)
                .findFirst()
                .orElse(rows.get(0));
        return reenqueue(target, requestedBy, reason);
    }

    private ReplayResult reenqueue(WebhookDeliveryEntity row, String requestedBy, String reason) {
        String status = row.getStatus();
        if (WebhookPersistenceService.STATUS_PENDING.equals(status)) {
            audit(row.getId(), row.getWebhookId(), requestedBy, reason, Outcome.NOOP_ALREADY_PENDING);
            return new ReplayResult(Outcome.NOOP_ALREADY_PENDING, row.getId());
        }
        if (WebhookPersistenceService.STATUS_DELIVERED.equals(status)) {
            audit(row.getId(), row.getWebhookId(), requestedBy, reason, Outcome.NOOP_ALREADY_DELIVERED);
            return new ReplayResult(Outcome.NOOP_ALREADY_DELIVERED, row.getId());
        }
        // DLQ or FAILED: re-enqueue. Reset the backoff clock (lastAttemptedAt=null +
        // attempt=0) so the drain treats this as a fresh delivery and sends immediately
        // rather than waiting on the exhausted-attempt backoff window.
        row.setStatus(WebhookPersistenceService.STATUS_PENDING);
        row.setAttempt(0);
        row.setLastAttemptedAt(null);
        row.setLastError(null);
        deliveryRepository.save(row);
        audit(row.getId(), row.getWebhookId(), requestedBy, reason, Outcome.REENQUEUED);
        log.info("webhook replay: delivery id={} webhookId={} re-enqueued to PENDING by {}",
                row.getId(), row.getWebhookId(), normalize(requestedBy));
        return new ReplayResult(Outcome.REENQUEUED, row.getId());
    }

    private static boolean isReplayable(WebhookDeliveryEntity row) {
        return WebhookPersistenceService.STATUS_DLQ.equals(row.getStatus())
                || WebhookPersistenceService.STATUS_FAILED.equals(row.getStatus());
    }

    private void audit(Long deliveryId, String webhookId, String requestedBy, String reason, Outcome outcome) {
        WebhookReplayAuditEntity audit = new WebhookReplayAuditEntity();
        audit.setDeliveryId(deliveryId == null ? -1L : deliveryId);
        audit.setWebhookId(webhookId == null ? "-" : webhookId);
        audit.setRequestedBy(normalize(requestedBy));
        audit.setReason(reason);
        audit.setOutcome(outcome.name());
        audit.setRequestedAt(Instant.now(clock));
        auditRepository.save(audit);
    }

    private static String normalize(String requestedBy) {
        return (requestedBy == null || requestedBy.isBlank()) ? "unknown" : requestedBy.trim();
    }
}
