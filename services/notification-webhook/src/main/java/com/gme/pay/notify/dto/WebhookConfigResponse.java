package com.gme.pay.notify.dto;

import java.time.Instant;
import java.util.List;

/**
 * Response DTO for webhook configuration read operations.
 *
 * <p>The signing secret is NEVER included in responses.
 */
public record WebhookConfigResponse(

        /** DB-assigned configuration identifier. */
        Long id,

        /** Partner this configuration belongs to. */
        Long partnerId,

        /** Registered HTTPS webhook URL. */
        String webhookUrl,

        /**
         * Subscribed event types; {@code null} means "all events".
         */
        List<String> eventTypes,

        /** Whether this endpoint is currently active. */
        boolean active,

        /** UTC timestamp when the configuration was created. */
        Instant createdAt,

        /** UTC timestamp of the last update, if any. */
        Instant updatedAt
) {}
