package com.gme.pay.gateway.partner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * The gateway's default {@link PartnerCredentialService} (T0-7): every api key presented at the
 * partner edge must be a credential that <b>auth-identity actually issued and still considers
 * active</b>, and the HMAC signing material must come from operator-supplied configuration —
 * never from anything checked into this repository.
 *
 * <h2>Why the responsibility is split</h2>
 *
 * <p>HMAC-SHA256 request signing needs the verifier to hold the <em>plaintext</em> secret. The
 * platform's real credential store deliberately cannot provide one: {@code api_keys} stores a
 * salted PBKDF2-HMAC-SHA256 digest and auth-identity discards the plaintext at issuance
 * (SEC-09 §4 — the property the CISO audit called out as genuinely solid and worth protecting).
 * So there is no way to make one component both "the authoritative store" and "the thing that
 * recomputes the HMAC". The split below is what is actually achievable without weakening
 * secret-at-rest:
 *
 * <ul>
 *   <li><b>auth-identity is authoritative for identity and lifecycle.</b> {@code found=false}
 *       (never issued) and {@code active=false} (revoked / expired) both mean 401. This is what
 *       makes revocation effective at the edge: before T0-7 the gateway had no idea keys were
 *       revocable, so revoking a partner key changed nothing about what the gateway accepted.</li>
 *   <li><b>Operator configuration is the source of signing material.</b>
 *       {@code gateway.partner-credentials.partners[].hmac-secret} must come from an environment
 *       variable / secret mount (see {@link ConfigPartnerCredentialProperties}), plus the per-partner
 *       edge policy the store does not model at all: IP CIDR ranges, mTLS fingerprint, quote TTL.</li>
 * </ul>
 *
 * <p>Consequence, stated plainly: <b>a key is accepted only if it appears in BOTH.</b> A key present
 * in config but revoked upstream is rejected; a key active upstream but absent from config is
 * rejected (the gateway has no secret to verify a signature with, so it cannot authenticate it —
 * logged as a WARN naming the misconfiguration rather than silently 401-ing). Neither half alone is
 * sufficient, which is strictly stronger than the previous "one hard-coded map, published in git".
 *
 * <h2>Order of checks</h2>
 *
 * <p>The local lookup runs first and short-circuits: an api key the gateway holds no material for
 * cannot be authenticated whatever the store says, so there is no point paying a network round-trip
 * — and, more importantly, an unauthenticated caller cannot use this edge to probe auth-identity or
 * to enumerate which keys exist upstream. Only a key the gateway could actually verify triggers the
 * lifecycle call.
 *
 * <p>Unreachable store ⇒ {@link PartnerCredentialSourceUnavailableException} ⇒ the filters answer
 * <b>503</b>. It is never downgraded to "assume active".
 */
public class AuthIdentityVerifiedPartnerCredentialService implements PartnerCredentialService {

    private static final Logger log =
            LoggerFactory.getLogger(AuthIdentityVerifiedPartnerCredentialService.class);

    private final PartnerCredentialService signingMaterialSource;
    private final AuthIdentityCredentialStatusClient statusClient;

    public AuthIdentityVerifiedPartnerCredentialService(
            PartnerCredentialService signingMaterialSource,
            AuthIdentityCredentialStatusClient statusClient) {
        this.signingMaterialSource = signingMaterialSource;
        this.statusClient = statusClient;
    }

    @Override
    public Mono<PartnerCredentials> findByApiKey(String apiKey) {
        return signingMaterialSource.findByApiKey(apiKey)
                .flatMap(creds -> statusClient.statusOf(apiKey).flatMap(status -> switch (status) {
                    case ACTIVE -> Mono.just(creds);
                    case UNKNOWN -> {
                        log.warn("api key configured on the gateway is not present in "
                                + "auth-identity's credential store (partner={}) — rejecting. A "
                                + "credential the platform never issued must not authenticate.",
                                creds.partnerId());
                        yield Mono.empty();
                    }
                    case INACTIVE -> {
                        log.warn("api key for partner={} is revoked or expired in auth-identity — "
                                + "rejecting even though it is still present in gateway config. "
                                + "Remove the stale row from gateway.partner-credentials.",
                                creds.partnerId());
                        yield Mono.empty();
                    }
                }));
    }
}
