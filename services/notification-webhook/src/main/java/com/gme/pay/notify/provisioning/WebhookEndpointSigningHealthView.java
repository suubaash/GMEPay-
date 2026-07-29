package com.gme.pay.notify.provisioning;

import java.time.Instant;

/**
 * Operator-facing signing state of one {@code webhook_endpoint} row — gap <b>T5-8</b>.
 *
 * <p>Answers "will this partner actually receive signed events, and if not, why?" without
 * disclosing anything about the secret: no plaintext, and not even the stored digest (which
 * is a verifier, not a display value). The only secret-adjacent field is
 * {@link #secretGeneration}, a rotation counter.
 *
 * <p>Deliberately NOT part of {@code lib-api-contracts}: this is an ops read, not a
 * cross-service contract, and the partner-activation contract
 * ({@code WebhookEndpointRegistrationView}) must not grow ops fields.
 *
 * @param endpointId              the {@code webhook_endpoint} row id (string, as every BFF id is)
 * @param partnerId               config-registry's numeric partner surrogate
 * @param environment             {@code SANDBOX} | {@code LIVE}
 * @param webhookUrl              the delivery target (no secret material)
 * @param secretGeneration        rotation counter; 1 = never rotated
 * @param status                  the {@link WebhookEndpointSigningStatus} name
 * @param deliverable             true only when deliveries for this endpoint can be signed
 * @param fixableByRotation       true when rotating this endpoint makes it deliverable again
 * @param detail                  operator-facing explanation, safe to render verbatim
 * @param rotationOverlapExpiresAt end of the dual-signature window, or {@code null}
 * @param createdAt               when the endpoint was registered — the age that tells an
 *                                operator whether this is a pre-T5-4 row
 * @param updatedAt               last mutation (registration or rotation)
 */
public record WebhookEndpointSigningHealthView(
        String endpointId,
        Long partnerId,
        String environment,
        String webhookUrl,
        int secretGeneration,
        String status,
        boolean deliverable,
        boolean fixableByRotation,
        String detail,
        Instant rotationOverlapExpiresAt,
        Instant createdAt,
        Instant updatedAt) {

    /** Builds the view for one classified endpoint. */
    public static WebhookEndpointSigningHealthView of(
            com.gme.pay.notify.persistence.WebhookEndpointEntity endpoint,
            WebhookEndpointSigningStatus status,
            Instant now) {
        Instant overlapExpiry = endpoint.getPreviousSecretExpiresAt();
        boolean overlapOpen = overlapExpiry != null && now != null && now.isBefore(overlapExpiry);
        return new WebhookEndpointSigningHealthView(
                endpoint.getId() == null ? null : String.valueOf(endpoint.getId()),
                endpoint.getPartnerId(),
                endpoint.getEnvironment(),
                endpoint.getWebhookUrl(),
                endpoint.getSecretGeneration(),
                status.name(),
                status.isDeliverable(),
                status.isFixableByRotation(),
                status.detail(),
                overlapOpen ? overlapExpiry : null,
                endpoint.getCreatedAt(),
                endpoint.getUpdatedAt());
    }
}
