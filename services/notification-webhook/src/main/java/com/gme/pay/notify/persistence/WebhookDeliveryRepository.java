package com.gme.pay.notify.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
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
     * Backlog gauge for the Ops backlog monitor: count of rows in a status created at
     * or before {@code cutoff}. Used to count only <em>overdue</em> PENDING rows
     * (older than the overdue window) so a healthy in-flight burst does not alert.
     */
    long countByStatusAndCreatedAtBefore(String status, Instant cutoff);

    /**
     * BOUNDED, FIFO drain query (#92): the oldest rows in a status, capped by
     * {@code pageable} so a backlog can never load the whole table into one drain's
     * heap. Oldest-first (createdAt) so no row is starved under a steady backlog.
     */
    List<WebhookDeliveryEntity> findByStatusOrderByCreatedAtAsc(String status, Pageable pageable);

    /**
     * The endpoints that currently have work in {@code status} — the first half of per-endpoint fair
     * selection (T3-11 defect 5, the design half).
     *
     * <p>May contain {@code null}: rows written before Flyway V009 carry no {@code partner_id} and are
     * handled as one unattributed group rather than dropped. Bounded by the number of registered
     * partners, not by the backlog, and served by
     * {@code idx_webhook_delivery_log_status_partner_created}.
     */
    @Query("SELECT DISTINCT d.partnerId FROM WebhookDeliveryEntity d WHERE d.status = :status")
    List<Long> findDistinctPartnerIdsByStatus(@Param("status") String status);

    /**
     * The oldest rows in {@code status} <b>for one partner</b>, capped by {@code pageable}.
     *
     * <p>This is what replaces the single global {@code ORDER BY created_at}: the drain asks each
     * endpoint with work for its own share, so a partner with a 10 000-row backlog can no longer fill
     * the whole batch and leave a healthy partner's three rows unselected. Oldest-first within the
     * partner, so nothing is starved inside a share either.
     */
    List<WebhookDeliveryEntity> findByStatusAndPartnerIdOrderByCreatedAtAsc(
            String status, Long partnerId, Pageable pageable);

    /** The same read for the unattributed (pre-V009 / no-partnerId) group; {@code = NULL} matches nothing. */
    List<WebhookDeliveryEntity> findByStatusAndPartnerIdIsNullOrderByCreatedAtAsc(
            String status, Pageable pageable);

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
