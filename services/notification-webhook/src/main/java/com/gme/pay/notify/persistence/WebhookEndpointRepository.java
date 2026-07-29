package com.gme.pay.notify.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring Data repository for {@link WebhookEndpointEntity}.
 */
public interface WebhookEndpointRepository extends JpaRepository<WebhookEndpointEntity, Long> {

    /** Returns all currently active endpoint registrations for the given partner. */
    List<WebhookEndpointEntity> findByPartnerIdAndActiveTrue(Long partnerId);

    /**
     * Active registrations for one partner in one credential environment
     * (V004) — the idempotency lookup of the Slice 8 registration endpoint.
     */
    List<WebhookEndpointEntity> findByPartnerIdAndEnvironmentAndActiveTrue(
            Long partnerId, String environment);

    /**
     * Every active registration, oldest first — the platform-wide sweep behind the T5-8
     * signing-health report ("which endpoints cannot be signed for?"). Ordered so the
     * pre-T5-4 rows, which are exactly the un-signable ones, surface at the top.
     */
    List<WebhookEndpointEntity> findByActiveTrueOrderByIdAsc();

    /** One partner's active registrations across environments, oldest first (T5-8 report). */
    List<WebhookEndpointEntity> findByPartnerIdAndActiveTrueOrderByIdAsc(Long partnerId);
}
