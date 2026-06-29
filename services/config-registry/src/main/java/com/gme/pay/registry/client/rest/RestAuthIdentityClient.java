package com.gme.pay.registry.client.rest;

import com.gme.pay.registry.client.AuthIdentityClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

/**
 * Production {@link AuthIdentityClient}: calls auth-identity's internal
 * key-issuance API via Spring 6 {@link RestClient}. Active when
 * {@code gmepay.auth-identity.client=rest}; base URL from
 * {@code gmepay.auth-identity.base-url} (default the compose-internal
 * {@code http://auth-identity:8080}) — the same conditional/@Primary wiring
 * pattern as {@code RestKybClient} / the BFF's {@code RestConfigRegistryClient}.
 *
 * <p>Endpoint mapping (auth-identity {@code ApiKeyAdminController} — mounted
 * under {@code /internal/auth}, the machine surface its
 * {@code WebSurfaceScopeTest} pins per ADR-011):
 * <ul>
 *   <li>{@code POST /internal/auth/keys}                → {@link #issueKey}</li>
 *   <li>{@code POST /internal/auth/keys/{keyId}/revoke} → {@link #revokeKey}</li>
 * </ul>
 *
 * <h2>Failure mapping</h2>
 *
 * <ul>
 *   <li>upstream 4xx → re-thrown with status + message preserved (a 400 from
 *       auth-identity is a caller bug worth surfacing verbatim);</li>
 *   <li>network failure / 5xx → 502 Bad Gateway: issuance is part of an
 *       activation transaction — the caller must know it did not happen so
 *       the whole transition rolls back (no partner ends up SANDBOX without
 *       credentials).</li>
 * </ul>
 *
 * <h2>Secret handling (SEC-09 §4)</h2>
 *
 * <p>The response body carries the one-time plaintext. It is mapped straight
 * into {@link AuthIdentityClient.IssuedKey} (whose {@code toString()}
 * redacts) and NEVER logged — failure logs carry the key prefix only.
 */
@Component
@Primary
@ConditionalOnProperty(name = "gmepay.auth-identity.client", havingValue = "rest")
public class RestAuthIdentityClient implements AuthIdentityClient {

    private final RestClient restClient;

    /**
     * Spring 6 trap (RestConfigRegistryClient / RestAuditTrailClient /
     * RestPartnerSchemeResolver lesson): with two constructors present the
     * {@code @Value} one MUST carry {@code @Autowired} or context startup
     * fails with "no default constructor".
     */
    @Autowired
    public RestAuthIdentityClient(
            @Value("${gmepay.auth-identity.base-url:http://auth-identity:8080}") String baseUrl,
            @Value("${gmepay.auth-identity.internal-secret:}") String internalSecret) {
        this(buildClient(baseUrl, internalSecret));
    }

    /**
     * auth-identity's {@code /internal/auth/keys} is an internal-only endpoint; when auth-identity
     * enforces the service-to-service internal-auth gate (#90), config-registry is a trusted caller
     * and must present the shared {@code X-Gme-Internal} token. Blank secret = local dev (gate off).
     */
    private static RestClient buildClient(String baseUrl, String internalSecret) {
        RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl);
        if (internalSecret != null && !internalSecret.isBlank()) {
            builder.defaultHeader(
                    com.gme.pay.internalauth.InternalAuthHeaders.INTERNAL_TOKEN, internalSecret);
        }
        return builder.build();
    }

    /** Package-private constructor for tests to inject a pre-built RestClient. */
    RestAuthIdentityClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public IssuedKey issueKey(IssueKeyCommand command) {
        try {
            IssueKeyResponse response = restClient.post()
                    .uri("/internal/auth/keys")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new IssueKeyRequest(
                            command.partnerId(),
                            command.partnerCode(),
                            command.environment(),
                            command.purpose(),
                            command.keyPrefix(),
                            command.secretPrefix(),
                            command.expiresAt()))
                    .retrieve()
                    .body(IssueKeyResponse.class);
            if (response == null || response.keyId() == null
                    || response.secretPlaintext() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "auth-identity returned an incomplete issuance response for prefix "
                                + command.keyPrefix());
            }
            return new IssuedKey(response.keyId(), response.secretPlaintext(),
                    response.expiresAt());
        } catch (RestClientResponseException upstream) {
            throw mapUpstream(upstream, "issue key with prefix " + command.keyPrefix());
        } catch (ResourceAccessException network) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "auth-identity unreachable while issuing key with prefix "
                            + command.keyPrefix() + ": " + network.getMessage());
        }
    }

    @Override
    public void revokeKey(String keyId) {
        try {
            restClient.post()
                    .uri("/internal/auth/keys/{keyId}/revoke", keyId)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
                        // 404 = already gone; revoke is idempotent by contract.
                    })
                    .toBodilessEntity();
        } catch (ResourceAccessException network) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "auth-identity unreachable while revoking key " + keyId + ": "
                            + network.getMessage());
        }
    }

    private static ResponseStatusException mapUpstream(RestClientResponseException upstream,
                                                       String action) {
        HttpStatusCode status = upstream.getStatusCode();
        if (status.is4xxClientError()) {
            return new ResponseStatusException(status,
                    "auth-identity rejected " + action + ": " + upstream.getResponseBodyAsString());
        }
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "auth-identity failed to " + action + " (" + status.value() + ")");
    }

    /**
     * Wire DTOs matching auth-identity's internal issuance contract. Mirrored
     * locally because importing auth-identity's internal records would
     * violate MSA rule 5 (service-owned domain models stay private) — same
     * note as {@code RestPartnerCredentialClient.PartnerResponse} over there.
     */
    record IssueKeyRequest(
            Long partnerId,
            String partnerCode,
            String environment,
            String purpose,
            String keyPrefix,
            String secretPrefix,
            Instant expiresAt) {
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record IssueKeyResponse(
            String keyId,
            String secretPlaintext,
            Instant expiresAt) {

        /** Redacting override — this record transiently holds plaintext (SEC-09 §4). */
        @Override
        public String toString() {
            return "IssueKeyResponse[keyId=" + keyId
                    + ", secretPlaintext=REDACTED, expiresAt=" + expiresAt + "]";
        }
    }
}
