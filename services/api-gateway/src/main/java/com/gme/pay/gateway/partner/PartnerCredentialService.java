package com.gme.pay.gateway.partner;

import reactor.core.publisher.Mono;

/**
 * Gateway-internal interface for resolving partner credentials by API key.
 *
 * <p>Implementations may cache results (Redis TTL 60 s) and back them with PostgreSQL
 * config-registry. In Phase-1 tests a stub implementation backed by application.yml is used.
 * This interface must NOT be shared with or implemented inside any other service module.
 */
public interface PartnerCredentialService {

    /**
     * Resolve credentials for the given raw API key value (from the X-API-Key header).
     *
     * @return the matching {@link PartnerCredentials}, or {@link Mono#empty()} if the key is
     *         unknown or has been revoked.
     */
    Mono<PartnerCredentials> findByApiKey(String apiKey);
}
