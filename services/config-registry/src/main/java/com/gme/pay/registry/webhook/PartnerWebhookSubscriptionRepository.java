package com.gme.pay.registry.webhook;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link PartnerWebhookSubscriptionEntity} (V030)
 * — Slice 8 Lane D.
 */
public interface PartnerWebhookSubscriptionRepository
        extends JpaRepository<PartnerWebhookSubscriptionEntity, Long> {

    /** The (at most one — V030 UNIQUE) subscription of a partner in an environment. */
    Optional<PartnerWebhookSubscriptionEntity> findByPartnerIdAndEnvironment(
            Long partnerId, String environment);

    /** All subscriptions of a partner (detail page / wizard rehydrate), stable order. */
    List<PartnerWebhookSubscriptionEntity> findByPartnerIdOrderByEnvironmentAsc(Long partnerId);
}
