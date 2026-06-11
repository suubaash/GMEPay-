package com.gme.pay.notify.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring Data repository for {@link WebhookEndpointEntity}.
 */
public interface WebhookEndpointRepository extends JpaRepository<WebhookEndpointEntity, Long> {

    /** Returns all currently active endpoint registrations for the given partner. */
    List<WebhookEndpointEntity> findByPartnerIdAndActiveTrue(Long partnerId);
}
