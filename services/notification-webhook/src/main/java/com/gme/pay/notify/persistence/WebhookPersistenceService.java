package com.gme.pay.notify.persistence;

import com.gme.pay.notify.domain.RetryPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Phase-1 persistence collaborator for {@code WebhookSender}.
 *
 * <p>Records each delivery attempt to {@code webhook_delivery_log} and promotes
 * the row to {@code webhook_dlq} once the configured {@link RetryPolicy} is
 * exhausted. This service is wired as an <strong>optional</strong> collaborator
 * &mdash; existing unit tests that construct {@code WebhookSender} without a
 * persistence service continue to work because the sender simply skips
 * persistence in that mode.
 *
 * <p>Plain Java &mdash; no Lombok &mdash; per project conventions.
 */
@Service
public class WebhookPersistenceService {

    /** Status values written to {@code webhook_delivery_log.status}. */
    public static final String STATUS_PENDING   = "PENDING";
    public static final String STATUS_DELIVERED = "DELIVERED";
    public static final String STATUS_FAILED    = "FAILED";
    public static final String STATUS_DLQ       = "DLQ";

    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookDlqRepository dlqRepository;
    private final RetryPolicy retryPolicy;
    private final Clock clock;

    public WebhookPersistenceService(WebhookDeliveryRepository deliveryRepository,
                                     WebhookDlqRepository dlqRepository,
                                     RetryPolicy retryPolicy,
                                     Clock clock) {
        this.deliveryRepository = Objects.requireNonNull(deliveryRepository);
        this.dlqRepository = Objects.requireNonNull(dlqRepository);
        this.retryPolicy = Objects.requireNonNull(retryPolicy);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Enqueues a webhook delivery for the dispatch pipeline (17.4-G04 Kafka consumer
     * entry point): inserts a {@code PENDING} row in {@code webhook_delivery_log}
     * with {@code attempt = 0} (no dispatch executed yet).
     *
     * <p>Idempotent for Kafka at-least-once redelivery: if a NON-DLQ row for the same
     * {@code webhookId} + {@code eventType} already exists (in-flight {@code PENDING} or
     * successful {@code DELIVERED}), nothing is inserted and {@link Optional#empty()} is
     * returned so the consumer can still ack. A prior row that was DLQ'd does NOT block —
     * re-publishing the event re-enqueues a fresh attempt (replay, #92), since a DLQ'd
     * delivery is terminally failed, not a live duplicate.
     *
     * @param webhookId  logical webhook id (the event's aggregate id)
     * @param eventType  event type (e.g. {@code payment.approved})
     * @param payload    serialized JSON payload as received from the topic
     * @return the new {@code PENDING} row, or empty if a live (non-DLQ) row existed
     */
    @Transactional
    public Optional<WebhookDeliveryEntity> enqueuePendingIfAbsent(String webhookId,
                                                                  String eventType,
                                                                  String payload) {
        Objects.requireNonNull(webhookId, "webhookId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        if (deliveryRepository.existsByWebhookIdAndEventTypeAndStatusNot(
                webhookId, eventType, STATUS_DLQ)) {
            return Optional.empty();
        }
        Instant now = Instant.now(clock);
        WebhookDeliveryEntity row = new WebhookDeliveryEntity();
        row.setWebhookId(webhookId);
        row.setEventType(eventType);
        row.setPayload(payload);
        row.setAttempt(0);
        row.setStatus(STATUS_PENDING);
        row.setCreatedAt(now);
        return Optional.of(deliveryRepository.save(row));
    }

    /**
     * Records a delivery attempt outcome.
     *
     * @param webhookId  logical webhook id (e.g. {@code evt_01HXXX...})
     * @param eventType  event type (e.g. {@code payment.approved})
     * @param payload    serialized JSON payload
     * @param attempt    1-based attempt number that just executed
     * @param success    {@code true} if the partner returned 2xx
     * @param error      error detail if {@code !success}; may be {@code null}
     * @return the persisted {@link WebhookDeliveryEntity}
     */
    @Transactional
    public WebhookDeliveryEntity recordAttempt(String webhookId,
                                               String eventType,
                                               String payload,
                                               int attempt,
                                               boolean success,
                                               String error) {
        Instant now = Instant.now(clock);
        WebhookDeliveryEntity row = new WebhookDeliveryEntity();
        row.setWebhookId(webhookId);
        row.setEventType(eventType);
        row.setPayload(payload);
        row.setAttempt(attempt);
        row.setLastAttemptedAt(now);
        row.setCreatedAt(now);
        if (success) {
            row.setStatus(STATUS_DELIVERED);
            row.setDeliveredAt(now);
        } else {
            row.setStatus(STATUS_FAILED);
            row.setLastError(error);
        }
        return deliveryRepository.save(row);
    }

    /**
     * Promotes a failed delivery row to the DLQ. Writes a {@link WebhookDlqEntity}
     * and flips the originating delivery-log row's status to {@code DLQ}.
     */
    @Transactional
    public WebhookDlqEntity moveToDlq(WebhookDeliveryEntity originating, String reason) {
        Objects.requireNonNull(originating, "originating must not be null");
        Objects.requireNonNull(originating.getId(), "originating must be persisted");

        originating.setStatus(STATUS_DLQ);
        deliveryRepository.save(originating);

        WebhookDlqEntity dlq = new WebhookDlqEntity();
        dlq.setOriginalId(originating.getId());
        dlq.setWebhookId(originating.getWebhookId());
        dlq.setPayload(originating.getPayload());
        dlq.setReason(reason);
        dlq.setAddedAt(Instant.now(clock));
        return dlqRepository.save(dlq);
    }

    /**
     * Advances an existing PENDING row to DELIVERED in place (dispatcher drain path).
     * Unlike {@link #recordAttempt} this updates the originating row rather than
     * inserting a new one, so the drain loop does not re-pick the same event.
     *
     * @param row     the row that was just delivered (must be persisted)
     * @param attempt the 1-based attempt number that succeeded
     */
    @Transactional
    public void markDelivered(WebhookDeliveryEntity row, int attempt) {
        Objects.requireNonNull(row, "row must not be null");
        Instant now = Instant.now(clock);
        row.setStatus(STATUS_DELIVERED);
        row.setAttempt(attempt);
        row.setLastAttemptedAt(now);
        row.setDeliveredAt(now);
        row.setLastError(null);
        deliveryRepository.save(row);
    }

    /**
     * Advances an existing row after a failed attempt (dispatcher drain path):
     * records the error and either promotes to DLQ (threshold reached) or leaves the
     * row PENDING for a later retry (gated by {@link RetryPolicy} backoff on
     * {@code lastAttemptedAt}). Updates the originating row in place.
     *
     * @param row     the row whose attempt failed (must be persisted)
     * @param attempt the 1-based attempt number that just failed
     * @param error   error detail; may be {@code null}
     */
    @Transactional
    public void markAttemptFailedOrDlq(WebhookDeliveryEntity row, int attempt, String error) {
        Objects.requireNonNull(row, "row must not be null");
        Instant now = Instant.now(clock);
        row.setAttempt(attempt);
        row.setLastAttemptedAt(now);
        row.setLastError(error);
        if (retryPolicy.isDlqThresholdReached(attempt)) {
            // moveToDlq flips status to DLQ, saves the row, and writes the DLQ entry.
            moveToDlq(row, error == null ? "max attempts exhausted" : error);
        } else {
            row.setStatus(STATUS_PENDING);
            deliveryRepository.save(row);
        }
    }

    /**
     * Records an attempt; if the attempt failed and the retry policy threshold is
     * reached, also moves the row to the DLQ. Convenience entry point for
     * {@code WebhookSender} integration.
     */
    @Transactional
    public WebhookDeliveryEntity recordAttemptAndMaybeDlq(String webhookId,
                                                         String eventType,
                                                         String payload,
                                                         int attempt,
                                                         boolean success,
                                                         String error) {
        WebhookDeliveryEntity row = recordAttempt(webhookId, eventType, payload, attempt, success, error);
        if (!success && retryPolicy.isDlqThresholdReached(attempt)) {
            moveToDlq(row, error == null ? "max attempts exhausted" : error);
        }
        return row;
    }
}
