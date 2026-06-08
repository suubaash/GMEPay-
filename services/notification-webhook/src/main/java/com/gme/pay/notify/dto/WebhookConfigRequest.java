package com.gme.pay.notify.dto;

import java.util.List;

/**
 * Request body for registering or updating a partner webhook endpoint.
 *
 * <p>Validates that {@code webhookUrl} starts with {@code https://} before persisting.
 */
public record WebhookConfigRequest(

        /** Partner identifier (numeric). */
        Long partnerId,

        /**
         * HTTPS URL of the partner's webhook receiver endpoint.
         * Must start with {@code https://}.
         */
        String webhookUrl,

        /**
         * Comma-separated list of event types to subscribe to.
         * {@code null} or empty means "subscribe to all events".
         * Example: {@code ["payment.approved", "payment.failed"]}
         */
        List<String> eventTypes,

        /**
         * HMAC signing secret for this endpoint (plaintext; stored in Vault, never in DB).
         * Only present on creation/rotation requests — never returned in responses.
         */
        String signingSecret
) {}
