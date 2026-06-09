package com.gme.pay.notify.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring Data repository for {@link WebhookDlqEntity}.
 */
public interface WebhookDlqRepository extends JpaRepository<WebhookDlqEntity, Long> {

    /** Returns DLQ entries linked back to the originating delivery-log row id. */
    List<WebhookDlqEntity> findByOriginalId(Long originalId);

    /** Returns all DLQ entries for a given logical webhook id. */
    List<WebhookDlqEntity> findByWebhookId(String webhookId);
}
