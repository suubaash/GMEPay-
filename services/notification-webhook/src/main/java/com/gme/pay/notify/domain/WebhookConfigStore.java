package com.gme.pay.notify.domain;

import com.gme.pay.notify.dto.WebhookConfigResponse;

import java.util.List;
import java.util.Optional;

/**
 * Port (interface) for persisting and retrieving per-partner webhook configurations.
 *
 * <p>Production implementation connects to the {@code partner_webhook} PostgreSQL table
 * (Flyway migration V31). Tests inject a stub or an in-memory implementation.
 */
public interface WebhookConfigStore {

    /** Persists a new webhook configuration and returns the saved state. */
    WebhookConfigResponse save(WebhookConfigEntry entry);

    /** Returns all active configurations for the given partner. */
    List<WebhookConfigResponse> findActiveByPartnerId(Long partnerId);

    /** Looks up a specific configuration by its primary key. */
    Optional<WebhookConfigResponse> findById(Long id);

    /** Deactivates (soft-deletes) the configuration with the given id. */
    void deactivate(Long id);
}
