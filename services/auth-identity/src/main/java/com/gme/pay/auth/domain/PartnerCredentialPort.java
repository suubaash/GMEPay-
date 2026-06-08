package com.gme.pay.auth.domain;

import java.util.Optional;

/**
 * Port — collaborator interface for resolving partner credentials.
 *
 * The production implementation calls the config-registry service API
 * (POST /internal/v1/credentials/resolve) over the internal mTLS network.
 *
 * Tests inject a simple in-memory stub without any network or Docker dependency.
 *
 * Per MSA rules: this service MUST NOT import config-registry's private entities or DB.
 */
public interface PartnerCredentialPort {

    /**
     * Resolves the active credential for the given API key.
     *
     * @param apiKey the X-API-Key header value
     * @return credential details if the key is known and active, or empty
     */
    Optional<ResolvedCredential> findActiveByApiKey(String apiKey);

    /**
     * Value object returned by the port.  Contains only what this service needs.
     */
    record ResolvedCredential(Long partnerId, String hmacSecret) {}
}
