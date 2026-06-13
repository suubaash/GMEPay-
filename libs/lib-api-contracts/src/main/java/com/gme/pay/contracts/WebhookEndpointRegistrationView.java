package com.gme.pay.contracts;

/**
 * Cross-service wire response of notification-webhook
 * {@code POST /v1/webhooks/endpoints} (Slice 8 Lane D) — the counterpart of
 * {@link WebhookEndpointRegistrationCommand}.
 *
 * <ul>
 *   <li>{@code endpointId} — the {@code webhook_endpoint} row id as an opaque
 *       STRING (cross-service references are id-strings, never joined
 *       BIGINTs).</li>
 *   <li>{@code signingSecretPlaintext} — the freshly generated HMAC-SHA256
 *       signing secret. Present ONLY when {@code newlyRegistered} is true;
 *       the callee stores a SHA-256 hash at rest and can never replay the
 *       plaintext. On the idempotent path (an active endpoint already exists
 *       for the partner + environment) this is {@code null}.</li>
 *   <li>{@code newlyRegistered} — true when a new endpoint row + secret were
 *       created by this call.</li>
 * </ul>
 */
public record WebhookEndpointRegistrationView(
        String endpointId,
        String signingSecretPlaintext,
        boolean newlyRegistered) {
}
