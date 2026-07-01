package com.gme.pay.notify.replay;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring Data repository for {@link WebhookReplayAuditEntity} — the operator
 * webhook-replay audit ledger.
 */
public interface WebhookReplayAuditRepository extends JpaRepository<WebhookReplayAuditEntity, Long> {

    /** All replay-audit rows for a given delivery, newest tracing done by caller. */
    List<WebhookReplayAuditEntity> findByDeliveryId(Long deliveryId);
}
