package com.gme.pay.notify.provisioning;

import java.time.Instant;

/**
 * Result of {@link WebhookEndpointProvisioningService#rotateSecret} — gap T5-4.
 *
 * <p>Local to this module deliberately: the registration contract
 * ({@code com.gme.pay.contracts.WebhookEndpointRegistrationView}) is shared with
 * config-registry and must not grow rotation fields, and no caller of rotation exists
 * yet (see the T5-4 report for the ops/config-registry follow-up).
 *
 * @param endpointId              the rotated {@code webhook_endpoint} row
 * @param signingSecretPlaintext  the NEW secret — revealed exactly once, never re-readable
 * @param secretGeneration        the new generation counter (HKDF {@code info} input)
 * @param previousSecretExpiresAt end of the overlap window during which the retired
 *                                secret is still sent as a second signature;
 *                                {@code null} for an immediate cutover
 */
public record WebhookSecretRotationView(
        String endpointId,
        String signingSecretPlaintext,
        int secretGeneration,
        Instant previousSecretExpiresAt) {
}
