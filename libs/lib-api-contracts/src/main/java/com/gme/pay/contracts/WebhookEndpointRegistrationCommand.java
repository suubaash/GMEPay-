package com.gme.pay.contracts;

import java.util.List;

/**
 * Cross-service wire request: config-registry &rarr; notification-webhook
 * {@code POST /v1/webhooks/endpoints} (Slice 8 Lane D). Registers (or
 * idempotently re-resolves) one partner webhook endpoint and has the
 * notification-webhook service generate the HMAC signing secret.
 *
 * <p>Lives in lib-api-contracts per the "DTOs are defined once, consumed by
 * every service" rule — {@code RestNotificationWebhookClient} (caller) and
 * {@code WebhookEndpointController} (callee) bind the SAME record.
 *
 * <ul>
 *   <li>{@code partnerId} — the partner's BIGINT surrogate id (the V003/V004
 *       {@code partners.id}), which is also what
 *       {@code webhook_endpoint.partner_id} stores.</li>
 *   <li>{@code url} — HTTPS receiver URL (&le; 512 chars).</li>
 *   <li>{@code eventTypes} — subscribed event types; {@code null}/empty =
 *       all events.</li>
 *   <li>{@code environment} — {@code SANDBOX} | {@code LIVE}.</li>
 * </ul>
 */
public record WebhookEndpointRegistrationCommand(
        Long partnerId,
        String url,
        List<String> eventTypes,
        String environment) {
}
