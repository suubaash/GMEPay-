package com.gme.pay.contracts;

import java.time.Instant;
import java.util.List;

/**
 * Read shape for one partner webhook subscription row
 * ({@code partner_webhook_subscription}, V030) — Slice 8 Lane D. Powers the
 * wizard's step-8 rehydrate and the partner detail page's webhook tile.
 *
 * <p>SECURITY: the signing secret NEVER appears here — not even its hash. The
 * plaintext is surfaced exactly once at provisioning time via
 * {@link IssuedWebhookSubscription}; afterwards only its existence is
 * observable ({@code status=PROVISIONED} + {@code lastRotatedAt}).
 *
 * <ul>
 *   <li>{@code endpointId} — the notification-webhook service's endpoint id
 *       as an opaque string ("FK across services by id-string"); {@code null}
 *       while the subscription is still a draft.</li>
 *   <li>{@code status} — {@code DRAFT} (saved, not yet provisioned),
 *       {@code PROVISIONED} (endpoint registered + secret issued) or
 *       {@code DISABLED}.</li>
 *   <li>{@code lastRotatedAt} — when the signing secret was last
 *       issued/rotated; {@code null} until first provisioning.</li>
 * </ul>
 */
public record WebhookSubscriptionView(
        Long id,
        String environment,
        String url,
        List<String> eventTypes,
        String endpointId,
        String status,
        Instant lastRotatedAt,
        Instant createdAt,
        Instant updatedAt) {
}
