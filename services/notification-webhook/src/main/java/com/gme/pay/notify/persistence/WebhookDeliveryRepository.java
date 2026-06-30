package com.gme.pay.notify.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring Data repository for {@link WebhookDeliveryEntity}.
 */
public interface WebhookDeliveryRepository extends JpaRepository<WebhookDeliveryEntity, Long> {

    /** Returns all delivery rows for a given logical webhook id, useful for Ops audit views. */
    List<WebhookDeliveryEntity> findByWebhookId(String webhookId);

    /** Returns rows in a given status (e.g. {@code FAILED}, {@code PENDING}). */
    List<WebhookDeliveryEntity> findByStatus(String status);

    /**
     * Cheap backlog gauge (WBS 8.6-T24): count of rows in a status, used by the
     * dispatcher to fire the queue-depth alert without materialising rows.
     */
    long countByStatus(String status);

    /**
     * BOUNDED, FIFO drain query (#92): the oldest rows in a status, capped by
     * {@code pageable} so a backlog can never load the whole table into one drain's
     * heap. Oldest-first (createdAt) so no row is starved under a steady backlog.
     */
    List<WebhookDeliveryEntity> findByStatusOrderByCreatedAtAsc(String status, Pageable pageable);

    /**
     * Idempotency probe for the Kafka consumer (at-least-once delivery): {@code true}
     * if a delivery row for this logical webhook id + event type already exists.
     */
    boolean existsByWebhookIdAndEventType(String webhookId, String eventType);

    /**
     * Idempotency probe that IGNORES terminally-dead rows (#92): {@code true} only if a
     * row exists whose status is NOT {@code status} (pass {@code DLQ}). Lets a
     * re-published event re-enqueue after its prior attempt was DLQ'd (replay) while
     * still de-duping against an in-flight {@code PENDING} or a successful
     * {@code DELIVERED} row.
     */
    boolean existsByWebhookIdAndEventTypeAndStatusNot(String webhookId, String eventType, String status);
}
