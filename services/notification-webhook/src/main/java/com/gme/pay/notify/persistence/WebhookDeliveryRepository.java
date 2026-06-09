package com.gme.pay.notify.persistence;

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
}
