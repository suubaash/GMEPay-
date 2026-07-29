package com.gme.pay.notify.dispatcher;

import com.gme.pay.notify.persistence.WebhookDeliveryEntity;

import java.util.Optional;

/**
 * Resolves the delivery target — partner endpoint URL + plaintext signing secret —
 * for a PENDING {@link WebhookDeliveryEntity}.
 *
 * <p>This is the one seam between "we have an event to deliver" and "we know where +
 * how to sign it". The endpoint URL comes from the {@code webhook_endpoint} table
 * (registered at partner activation, Slice 8 Lane D). The plaintext signing secret is
 * <b>never persisted</b> — only its SHA-256 hash is (see {@code WebhookEndpointEntity});
 * since T5-4 the secret is <b>per endpoint</b> and re-derived at dispatch time from a
 * root key plus the endpoint's identity, then checked against that stored hash
 * (see {@link DefaultWebhookTargetResolver}).
 *
 * <p>Returning {@link Optional#empty()} means "cannot deliver yet" — the dispatcher
 * leaves the row PENDING (it does not fail it) so delivery resumes once the endpoint /
 * secret becomes resolvable. An implementation must NEVER substitute a shared or
 * placeholder secret to make a delivery go out.
 */
public interface WebhookTargetResolver {

    /**
     * @param row the PENDING delivery row (carries webhookId, eventType, payload)
     * @return the resolved target, or empty when the endpoint or secret is unavailable
     */
    Optional<ResolvedTarget> resolve(WebhookDeliveryEntity row);

    /**
     * Partner endpoint URL + the plaintext HMAC secret(s) used to sign the payload.
     *
     * @param url             HTTPS endpoint to POST to
     * @param secret          the endpoint's current signing secret
     * @param secondarySecret the previous generation's secret while a rotation overlap
     *                        window is open, else {@code null}; when present the sender
     *                        emits a second signature so the partner can cut over
     *                        without dropping events (T5-4)
     */
    record ResolvedTarget(String url, String secret, String secondarySecret) {

        /** No rotation in progress — sign with one secret. */
        public ResolvedTarget(String url, String secret) {
            this(url, secret, null);
        }
    }
}
