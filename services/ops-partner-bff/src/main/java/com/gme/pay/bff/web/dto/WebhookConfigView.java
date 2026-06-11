package com.gme.pay.bff.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * Partner Portal webhook-config row. The BFF returns a list of these so the
 * Portal UI can render the webhook configuration page. Owned by
 * notification-webhook in production; Phase-1 returns 1-2 fake entries.
 */
public record WebhookConfigView(
        String url,
        List<String> eventTypes,
        String status,
        Instant lastDeliveredAt
) {}
