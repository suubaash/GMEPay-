package com.gme.pay.notify.domain;

import java.util.List;

/**
 * Immutable command object for registering a new webhook endpoint configuration.
 *
 * <p>This is passed from the controller to the store; the signing secret is held here
 * transiently and must be routed to Vault — it is never stored in the relational DB.
 */
public record WebhookConfigEntry(
        Long partnerId,
        String webhookUrl,
        List<String> eventTypes,
        String signingSecret
) {
    public WebhookConfigEntry {
        if (webhookUrl != null && !webhookUrl.startsWith("https://")) {
            throw new WebhookUrlNotHttpsException(
                    "Webhook URL must use HTTPS, got: " + webhookUrl);
        }
    }
}
