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
 * <b>never persisted</b> — only its SHA-256 hash is (see {@code WebhookEndpointEntity}),
 * with the plaintext "routed to Vault for dispatch-time signing". Until the Vault is
 * stood up (audit P1 — "secrets in Vault in-memory"), the default implementation reads
 * the secret from configuration as a local/dev stand-in.
 *
 * <p>Returning {@link Optional#empty()} means "cannot deliver yet" — the dispatcher
 * leaves the row PENDING (it does not fail it) so delivery resumes once the endpoint /
 * secret becomes resolvable.
 */
public interface WebhookTargetResolver {

    /**
     * @param row the PENDING delivery row (carries webhookId, eventType, payload)
     * @return the resolved target, or empty when the endpoint or secret is unavailable
     */
    Optional<ResolvedTarget> resolve(WebhookDeliveryEntity row);

    /** Partner endpoint URL + the plaintext HMAC secret used to sign the payload. */
    record ResolvedTarget(String url, String secret) {}
}
