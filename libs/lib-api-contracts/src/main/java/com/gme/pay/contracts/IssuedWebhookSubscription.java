package com.gme.pay.contracts;

import java.util.List;

/**
 * Result of provisioning one partner webhook subscription at activation —
 * Slice 8 Lane D. This is the webhook leg of the one-time credential reveal:
 * when a partner transitions to {@code SANDBOX} (and later {@code LIVE}) the
 * activation transaction registers the draft webhook subscription with the
 * notification-webhook service and surfaces the freshly generated signing
 * secret HERE, exactly once, alongside Lane B's API-key/HMAC material.
 *
 * <p>Lane B owns the composite {@code IssuedCredentialBundle}; this record is
 * designed to ride on it as a field once that bundle ships. Until then the
 * activation caller receives it directly from
 * {@code WebhookProvisioningService}.
 *
 * <ul>
 *   <li>{@code signingSecretPlaintext} — the HMAC-SHA256 signing secret in
 *       plaintext. Present ONLY when {@code newlyProvisioned} is true; the
 *       secret is stored hashed at rest on both sides and can never be
 *       re-read. Idempotent re-provisioning (the partner is already wired for
 *       the environment) returns the existing {@code endpointId} with a
 *       {@code null} secret — a new secret is NOT minted.</li>
 *   <li>{@code endpointId} — the notification-webhook endpoint id as an
 *       opaque string ("FK across services by id-string").</li>
 *   <li>{@code newlyProvisioned} — true when this call actually registered
 *       the endpoint and minted the secret; false on the idempotent
 *       short-circuit.</li>
 * </ul>
 */
public record IssuedWebhookSubscription(
        String environment,
        String endpointId,
        String url,
        List<String> eventTypes,
        String signingSecretPlaintext,
        boolean newlyProvisioned) {
}
