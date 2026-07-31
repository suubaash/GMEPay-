package com.gme.pay.gateway.partner;

import reactor.core.publisher.Mono;

/**
 * Gateway-internal interface for resolving partner credentials by API key.
 *
 * <p>This interface must NOT be shared with or implemented inside any other service module.
 *
 * <h2>Fail-closed contract (T0-7)</h2>
 *
 * <p>Every filter at the partner edge treats an empty result as "reject with 401". An
 * implementation therefore has exactly three permitted outcomes, and <b>fabricating a credential
 * is never one of them</b>:
 *
 * <ul>
 *   <li><b>a credential</b> — the key is known to the platform's real credential store AND is
 *       currently active;</li>
 *   <li>{@link Mono#empty()} — the key is unknown, revoked, expired, or the gateway holds no
 *       signing material for it. The caller answers <b>401</b>;</li>
 *   <li>{@link Mono#error} with {@link PartnerCredentialSourceUnavailableException} — the
 *       credential store could not be consulted (unconfigured, unreachable, 5xx, timeout). The
 *       caller answers <b>503</b>. An implementation must never downgrade this to
 *       {@code Mono.empty()} + a cached/assumed answer, and must never admit the request.</li>
 * </ul>
 *
 * <p>Before T0-7 the default implementation was a hard-coded stub whose api keys and HMAC secrets
 * were published in this repository, so anyone holding a checkout could sign requests as a real
 * partner against a default-configured gateway. There is no stub in the shipped build any more:
 * see {@link PartnerCredentialConfig} for the source roster and what an operator must supply.
 */
public interface PartnerCredentialService {

    /**
     * Resolve credentials for the given raw API key value (from the X-API-Key header).
     *
     * @return the matching {@link PartnerCredentials}, or {@link Mono#empty()} if the key is
     *         unknown, revoked, expired, or unusable.
     * @throws PartnerCredentialSourceUnavailableException signalled through
     *         {@link Mono#error} when the credential store cannot be consulted at all.
     */
    Mono<PartnerCredentials> findByApiKey(String apiKey);
}
