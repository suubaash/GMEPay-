package com.gme.pay.notify.provisioning;

import com.gme.pay.notify.persistence.WebhookEndpointEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The single implementation of the rule "may we sign for this endpoint?" — gaps T5-4 / T5-8.
 *
 * <p>Extracted so that the <b>dispatcher</b>
 * ({@code DefaultWebhookTargetResolver}, which enforces the rule) and the <b>operator read</b>
 * ({@link WebhookEndpointProvisioningService#signingHealth}, which reports it) can never
 * disagree. Before T5-8 the rule lived only inside the resolver, which is why the only
 * evidence that an endpoint was un-signable was an ERROR line at delivery time.
 *
 * <p>The rule: re-derive the endpoint's secret for a generation and accept it ONLY if it
 * hashes to the digest the row stored when that secret was revealed to the partner. Anything
 * else — no root key, no digest, a digest of randomness that predates derivation, a changed
 * root key — yields {@code null} / a non-{@link WebhookEndpointSigningStatus#SIGNABLE}
 * status, and the caller fails closed. There is deliberately no "sign with something else"
 * branch: a wrong signature is worse than no delivery, because the partner cannot tell it
 * from a forgery.
 *
 * <p>Stateless static helpers taking the deriver as an argument — no Spring wiring, so the
 * resolver's and the provisioning service's constructors are unchanged.
 */
public final class WebhookSecretVerifier {

    private static final Logger log = LoggerFactory.getLogger(WebhookSecretVerifier.class);

    private WebhookSecretVerifier() {
        // static utility
    }

    /**
     * Re-derives one generation's secret and proves it is the one the row was created with.
     *
     * @return the plaintext secret, or {@code null} when that cannot be proven — the caller
     *         must fail closed (leave the delivery PENDING), never substitute another key
     */
    public static String deriveAndVerify(WebhookSecretDeriver deriver,
                                         WebhookEndpointEntity endpoint,
                                         int generation,
                                         String expectedHash) {
        if (expectedHash == null || expectedHash.isBlank()) {
            // Legacy V003 row (secret was Vault-only) — nothing to verify against.
            return null;
        }
        String candidate;
        try {
            candidate = deriver.derive(endpoint.getPartnerId(), endpoint.getEnvironment(), generation);
        } catch (RuntimeException e) {
            log.error("webhook secret derivation failed for endpointId={} generation={}: {}",
                    endpoint.getId(), generation, e.getMessage());
            return null;
        }
        return SigningSecrets.matches(candidate, expectedHash) ? candidate : null;
    }

    /**
     * Classifies one endpoint without revealing anything about its secret — the read behind
     * the T5-8 operator report.
     *
     * <p>Ordering matters: a missing root key is reported as
     * {@link WebhookEndpointSigningStatus#ROOT_KEY_MISSING} even for a row that has no digest
     * at all, because "fix the platform configuration" is the actionable instruction and
     * rotation is refused in that state anyway.
     */
    public static WebhookEndpointSigningStatus statusOf(WebhookSecretDeriver deriver,
                                                        WebhookEndpointEntity endpoint) {
        if (!deriver.isConfigured()) {
            return WebhookEndpointSigningStatus.ROOT_KEY_MISSING;
        }
        String storedHash = endpoint.getSigningSecretHash();
        if (storedHash == null || storedHash.isBlank()) {
            return WebhookEndpointSigningStatus.NO_SECRET_DIGEST;
        }
        String verified =
                deriveAndVerify(deriver, endpoint, endpoint.getSecretGeneration(), storedHash);
        return verified == null
                ? WebhookEndpointSigningStatus.SECRET_NOT_DERIVABLE
                : WebhookEndpointSigningStatus.SIGNABLE;
    }
}
