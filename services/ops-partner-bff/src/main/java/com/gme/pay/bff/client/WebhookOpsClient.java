package com.gme.pay.bff.client;

import java.time.Instant;
import java.util.List;

/**
 * Ops view of the notification-webhook delivery pipeline: the backlog gauge for the
 * control-tower (PENDING + DLQ counts) and the operator replay action. Production calls
 * notification-webhook; the Phase-1 default
 * {@link com.gme.pay.bff.client.stub.StubWebhookOpsClient} is an in-memory stub.
 *
 * <p>Endpoint mapping (notification-webhook):
 * <ul>
 *   <li>{@code GET  /v1/webhooks/deliveries/backlog} -> {@link #backlog()}</li>
 *   <li>{@code POST /v1/webhooks/deliveries/{id}/replay} -> {@link #replay(String, String)}</li>
 *   <li>{@code GET  /v1/webhooks/endpoints/signing-health} -> {@link #endpointSigningHealth(Long)}</li>
 *   <li>{@code POST /v1/webhooks/endpoints/{id}/rotate-secret} -> {@link #rotateEndpointSecret(String, Long)}</li>
 * </ul>
 *
 * <h2>Why the last two exist (gap T5-8)</h2>
 *
 * <p>T5-4 replaced one global webhook HMAC key with per-endpoint derived secrets and made the
 * dispatcher refuse to sign when a secret cannot be re-derived and verified. Endpoints minted
 * before that change can never be re-derived (their plaintext was never stored), so they
 * stopped delivering — correctly, but invisibly. Rotation was the remedy and it had
 * <b>no caller anywhere</b>. These two methods are how an operator now sees the dead endpoints
 * and revives one.
 */
public interface WebhookOpsClient {

    /**
     * The current delivery backlog. Never throws — degrades to {@link WebhookBacklog#UNKNOWN}
     * (both counts null) when notification-webhook is unreachable, so the control-tower
     * shows the section as "unknown" rather than 500ing.
     */
    WebhookBacklog backlog();

    /**
     * Replay one webhook delivery by id. Returns the outcome; propagates upstream 4xx
     * (unknown delivery id) as a {@code ResponseStatusException} from the rest impl.
     */
    ReplayResult replay(String deliveryId, String actor);

    /**
     * Webhook delivery backlog gauge. {@code pending} = queued-but-undelivered,
     * {@code dlq} = dead-letter (exhausted retries). Null counts mean "unknown"
     * (upstream unavailable). {@link #total()} sums the two (null-safe).
     */
    record WebhookBacklog(Integer pending, Integer dlq) {
        /** All-unknown backlog used when the upstream cannot be reached. */
        public static final WebhookBacklog UNKNOWN = new WebhookBacklog(null, null);

        /** True when neither count is known. */
        public boolean unknown() {
            return pending == null && dlq == null;
        }

        /** Null-safe sum of pending + dlq; null when both are unknown. */
        public Integer total() {
            if (pending == null && dlq == null) {
                return null;
            }
            return (pending == null ? 0 : pending) + (dlq == null ? 0 : dlq);
        }
    }

    /**
     * Signing state of each active webhook endpoint (gap T5-8) — {@code partnerId} narrows to
     * one partner, {@code null} sweeps the platform.
     *
     * <p>Never throws: an unreachable upstream yields an EMPTY list, so the panel shows "no
     * data" rather than 500ing. Carries no secret material — see
     * {@link EndpointSigningHealth}.
     */
    List<EndpointSigningHealth> endpointSigningHealth(Long partnerId);

    /**
     * Rotates one endpoint's signing secret and returns the NEW plaintext — revealed exactly
     * once, like the partner-credential rotation flow (SEC-09 §4). The BFF never logs it and
     * never stores it; it exists only in this response.
     *
     * <p>Propagates the upstream status: 400 for an unknown/inactive endpoint or an
     * out-of-range window, 500 when no derivation root key is configured (notification-webhook
     * refuses to issue a secret its dispatcher could not reproduce).
     *
     * @param endpointId     the {@code webhook_endpoint} row to rotate
     * @param overlapMinutes how long the retired secret keeps being sent as a second
     *                       signature; {@code null} = upstream default (24 h), {@code 0} =
     *                       immediate cutover
     */
    RotatedWebhookSecret rotateEndpointSecret(String endpointId, Long overlapMinutes);

    /** Outcome of a single delivery replay. */
    record ReplayResult(String deliveryId, String status, String detail) {}

    /**
     * One endpoint's signing state (gap T5-8). Deliberately secret-free: no plaintext and not
     * even the stored digest — only a classification, so this record can be rendered, logged
     * and cached without care.
     *
     * @param status            {@code SIGNABLE} | {@code SECRET_NOT_DERIVABLE} |
     *                          {@code NO_SECRET_DIGEST} | {@code ROOT_KEY_MISSING}
     * @param deliverable       false ⇒ this partner is receiving NO webhooks right now
     * @param fixableByRotation true ⇒ rotating this endpoint revives it (and the partner must
     *                          then be told the new secret)
     * @param detail            operator-facing explanation, safe to render verbatim
     */
    record EndpointSigningHealth(
            String endpointId,
            Long partnerId,
            String environment,
            String webhookUrl,
            Integer secretGeneration,
            String status,
            boolean deliverable,
            boolean fixableByRotation,
            String detail,
            Instant rotationOverlapExpiresAt,
            Instant createdAt,
            Instant updatedAt) {
    }

    /**
     * A rotated endpoint secret — ONE-TIME plaintext. Must never be logged, persisted or
     * echoed anywhere but the single HTTP response that carries it to the operator.
     *
     * @param previousSecretExpiresAt end of the dual-signature overlap window; {@code null}
     *                                means the cutover was immediate
     */
    record RotatedWebhookSecret(
            String endpointId,
            String signingSecretPlaintext,
            Integer secretGeneration,
            Instant previousSecretExpiresAt) {
    }
}
