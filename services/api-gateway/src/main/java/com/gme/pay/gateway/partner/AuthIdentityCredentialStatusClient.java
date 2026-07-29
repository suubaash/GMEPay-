package com.gme.pay.gateway.partner;

import com.gme.pay.internalauth.InternalAuthHeaders;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Asks the platform's real credential store — auth-identity's {@code api_keys} table — whether an
 * {@code X-API-Key} is a credential it actually issued and whether that credential is still live.
 *
 * <p>Calls {@code POST /internal/auth/keys/resolve}
 * ({@code services/auth-identity/.../web/ApiKeyAdminController}), the endpoint T1-1 wired for
 * exactly this purpose. The response carries {@code found} / {@code active} / {@code partnerId} and
 * <b>never any secret material</b>: {@code api_keys} stores only a salted PBKDF2 digest of the
 * secret (SEC-09 §4), so the plaintext HMAC key is unrecoverable by design. See
 * {@link AuthIdentityVerifiedPartnerCredentialService} for what that means for the split of
 * responsibilities at the edge.
 *
 * <p>{@code /internal/auth/**} sits behind the shared internal-auth gate, so the shared
 * {@code X-Gme-Internal} token is presented on every call. <b>A blank token is fatal, not
 * optional</b>: it can only ever produce 401s from auth-identity, and this client reports that as
 * {@link Status#UNAVAILABLE} rather than pretending the key is unknown — an operator who forgot
 * {@code GMEPAY_INTERNAL_AUTH_SECRET} gets 503s and a startup-time WARN, never an open edge.
 */
public class AuthIdentityCredentialStatusClient {

    /** What the real credential store says about a presented api key. */
    public enum Status {
        /** A row exists and it is ACTIVE and unexpired. */
        ACTIVE,
        /** No row exists for this key — it was never issued by this platform. */
        UNKNOWN,
        /** A row exists but is REVOKED, PENDING_EXPIRY-past-expiry, or expired. */
        INACTIVE
    }

    private static final Logger log = LoggerFactory.getLogger(AuthIdentityCredentialStatusClient.class);

    /** Bounded so a hung credential store degrades to 503 rather than pinning event-loop threads. */
    static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final WebClient client;
    private final boolean internalTokenPresent;

    public AuthIdentityCredentialStatusClient(WebClient.Builder builder, String baseUrl,
                                              String internalSecret) {
        this.internalTokenPresent = internalSecret != null && !internalSecret.isBlank();
        WebClient.Builder b = builder.baseUrl(baseUrl);
        if (internalTokenPresent) {
            b.defaultHeader(InternalAuthHeaders.INTERNAL_TOKEN, internalSecret);
        } else {
            log.warn("api-gateway: gmepay.internal-auth.secret is blank, so partner-credential "
                    + "lifecycle checks against auth-identity ({}) can only ever 401. Every partner "
                    + "request will be answered 503 until GMEPAY_INTERNAL_AUTH_SECRET is set "
                    + "(T0-7: the edge fails CLOSED rather than skipping the check).", baseUrl);
        }
        this.client = b.build();
    }

    /** Package-private constructor for tests to inject a pre-built WebClient. */
    AuthIdentityCredentialStatusClient(WebClient client, boolean internalTokenPresent) {
        this.client = client;
        this.internalTokenPresent = internalTokenPresent;
    }

    /**
     * @return the store's verdict, or {@link Mono#error} with
     *         {@link PartnerCredentialSourceUnavailableException} when the store could not be
     *         consulted (blank internal token, transport failure, non-2xx, timeout, unparseable
     *         body). Never an empty {@link Mono} and never a guess.
     */
    public Mono<Status> statusOf(String apiKey) {
        if (!internalTokenPresent) {
            return Mono.error(new PartnerCredentialSourceUnavailableException(
                    "no internal-auth token configured; auth-identity's credential store cannot be "
                    + "consulted (set GMEPAY_INTERNAL_AUTH_SECRET)"));
        }
        if (apiKey == null || apiKey.isBlank()) {
            return Mono.just(Status.UNKNOWN);
        }
        return client.post()
                .uri("/internal/auth/keys/resolve")
                .bodyValue(Map.of("apiKey", apiKey))
                .retrieve()
                .bodyToMono(ResolveResponse.class)
                .timeout(TIMEOUT)
                .map(AuthIdentityCredentialStatusClient::toStatus)
                // A 2xx with an empty/unparseable body is not "unknown key", it is no answer.
                .switchIfEmpty(Mono.error(new PartnerCredentialSourceUnavailableException(
                        "auth-identity returned no body for the credential lookup")))
                .onErrorMap(e -> !(e instanceof PartnerCredentialSourceUnavailableException),
                        e -> {
                            // Never log the api key itself — it is a credential identifier.
                            log.error("partner-credential lookup against auth-identity failed: {} "
                                    + "— failing CLOSED (503)", e.toString());
                            return new PartnerCredentialSourceUnavailableException(
                                    "auth-identity credential lookup failed: " + e, e);
                        });
    }

    private static Status toStatus(ResolveResponse r) {
        if (r == null || !r.found()) {
            return Status.UNKNOWN;
        }
        return r.active() ? Status.ACTIVE : Status.INACTIVE;
    }

    /**
     * Mirrors auth-identity's {@code CredentialLookupResponse} JSON. Mirrored locally rather than
     * imported: MSA rule 5 keeps another service's DTOs out of this module. Primitive booleans
     * default to {@code false}, so a body missing a field degrades to UNKNOWN (deny), not to ACTIVE.
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record ResolveResponse(boolean found, boolean active, Long partnerId) { }
}
