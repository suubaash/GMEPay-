package com.gme.pay.bff.client.rest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.gme.pay.bff.client.ApiKeyClient;
import com.gme.pay.bff.client.PartnerDirectory;
import com.gme.pay.internalauth.InternalAuthHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Production {@link ApiKeyClient} — the real API-key registry behind the Partner Portal's
 * API Keys page (gap register T1-3).
 *
 * <p>Before this existed there was no rest implementation at all, so the page could only ever
 * render {@code StubApiKeyClient}'s two fabricated {@code gpk_live_…} keys. Those looked exactly
 * like production credentials while being generated from
 * {@code partnerId.hashCode()}. Active when {@code gmepay.auth-identity.client=rest} — the same
 * selector as its sibling {@link RestSandboxKeyClient}, because auth-identity owns key lifecycle
 * (ADR-011).
 *
 * <h2>Endpoint mapping</h2>
 *
 * <p>auth-identity's {@code ApiKeyAdminController}, on the {@code /internal/auth/keys} machine
 * surface: {@code GET /internal/auth/keys?partnerId={long}&environment={env}}. The endpoint is
 * scoped to ONE environment per call, so this client queries both rosters
 * ({@code PRODUCTION} then {@code SANDBOX}) and merges them newest-first — a partner's key list is
 * their whole credential set, and hiding the sandbox half would misrepresent it.
 *
 * <h2>Partner scoping (security-critical)</h2>
 *
 * <p>auth-identity keys credentials to config-registry's NUMERIC partner surrogate, while the
 * portal path/token carries the business CODE. {@link PartnerDirectory} bridges the two; when the
 * code does not resolve this client returns an EMPTY list rather than issuing an unscoped query.
 * The {@code partnerId} query param is always populated, so auth-identity can never be asked for
 * "all partners' keys".
 *
 * <h2>Secret hygiene (SEC-09 §4)</h2>
 *
 * <p>The upstream list surface carries no secret material by construction — {@code KeyListItem} has
 * no secret field and the store keeps only a salted PBKDF2 hash. This client surfaces the public
 * key id, its non-secret display {@code prefix}, the environment, the issuance instant and the real
 * lifecycle {@code status}. It NEVER requests, maps or logs a plaintext secret or a hash.
 *
 * <h2>Fields auth-identity genuinely does not hold</h2>
 *
 * <p>{@code name}, {@code scopes} and {@code lastUsedAt} have no column in the {@code api_keys}
 * table (V002) and no other owner in the platform, so they are returned as {@code null} / empty
 * rather than synthesised. The stub used to invent a {@code "Primary"}/{@code "Rotating"} label, a
 * two-scope list and a last-used instant; all three were fiction. See the T1-3 report for the
 * residual list.
 *
 * <h2>Internal auth (T0-2)</h2>
 *
 * <p>auth-identity's whole {@code /internal/**} surface is behind the service-to-service gate and
 * it refuses to boot without a secret, so this client presents
 * {@code gmepay.auth-identity.internal-secret} in the {@code X-Gme-Internal} header exactly as
 * {@link RestSandboxKeyClient} does. A blank secret sends no header and logs a WARN — fail-closed,
 * never a fabricated credential.
 */
@Component
@Primary
@ConditionalOnProperty(name = "gmepay.auth-identity.client", havingValue = "rest", matchIfMissing = true)
public class RestApiKeyClient implements ApiKeyClient {

    private static final Logger log = LoggerFactory.getLogger(RestApiKeyClient.class);

    /**
     * Credential rosters to merge, in display order. Mirrors auth-identity's
     * {@code ApiKeyIssuanceService.ENVIRONMENTS}; the endpoint rejects anything else.
     */
    static final List<String> ENVIRONMENTS = List.of("PRODUCTION", "SANDBOX");

    private final RestClient restClient;
    private final PartnerDirectory partners;

    @Autowired
    public RestApiKeyClient(
            @Value("${gmepay.auth-identity.base-url:http://auth-identity:8080}") String baseUrl,
            @Value("${gmepay.auth-identity.internal-secret:}") String internalSecret,
            PartnerDirectory partners) {
        this(builderFor(baseUrl, internalSecret).build(), partners);
    }

    /**
     * Builds the {@link RestClient.Builder} the production constructor uses: base URL plus, when a
     * secret is configured, the {@code X-Gme-Internal} default header. Package-private so a test can
     * bind a {@code MockRestServiceServer} to the very same builder and assert the header really
     * goes on the wire instead of trusting a hand-built client.
     */
    static RestClient.Builder builderFor(String baseUrl, String internalSecret) {
        RestClient.Builder b = RestClient.builder().baseUrl(baseUrl);
        if (internalSecret != null && !internalSecret.isBlank()) {
            b.defaultHeader(InternalAuthHeaders.INTERNAL_TOKEN, internalSecret);
        } else {
            log.warn("gmepay.auth-identity.internal-secret is blank — the partner API-key list will "
                    + "carry no {} header and a gated auth-identity will refuse it (401). Set "
                    + "GMEPAY_INTERNAL_AUTH_SECRET.", InternalAuthHeaders.INTERNAL_TOKEN);
        }
        return b;
    }

    /** Package-private constructor for tests to inject a pre-built RestClient. */
    RestApiKeyClient(RestClient restClient, PartnerDirectory partners) {
        this.restClient = restClient;
        this.partners = partners;
    }

    @Override
    public List<ApiKeyView> listForPartner(String partnerId) {
        Optional<Long> numericPartner = partners.numericIdOf(partnerId);
        if (numericPartner.isEmpty()) {
            // Fail closed: no resolvable partner surrogate means no keys to show. Never query
            // auth-identity without a partnerId — that would be a cross-partner read.
            log.warn("api-keys: partner '{}' has no config-registry surrogate id — returning an "
                    + "empty key list rather than an unscoped auth-identity query", partnerId);
            return List.of();
        }
        long partnerSurrogate = numericPartner.get();

        List<ApiKeyView> merged = new ArrayList<>();
        for (String environment : ENVIRONMENTS) {
            merged.addAll(listOne(partnerSurrogate, environment));
        }
        // Newest first across both rosters. Null createdAt sorts last rather than NPEing.
        merged.sort(Comparator.comparing(
                ApiKeyView::createdAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return List.copyOf(merged);
    }

    /** One environment's roster; upstream faults degrade that roster to empty, never to a fake. */
    private List<ApiKeyView> listOne(long partnerSurrogate, String environment) {
        try {
            String uri = UriComponentsBuilder.fromPath("/internal/auth/keys")
                    .queryParam("partnerId", partnerSurrogate)
                    .queryParam("environment", environment)
                    .build().toUriString();
            List<WireListItem> rows = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<WireListItem>>() {});
            if (rows == null) {
                return List.of();
            }
            return rows.stream().map(r -> r.toView(environment)).toList();
        } catch (RestClientResponseException e) {
            log.warn("auth-identity error listing {} api-keys for partner {} (status={})",
                    environment, partnerSurrogate, e.getStatusCode());
            return List.of();
        } catch (ResourceAccessException e) {
            log.warn("auth-identity unreachable listing {} api-keys for partner {}: {}",
                    environment, partnerSurrogate, e.getMessage());
            return List.of();
        }
    }

    /**
     * auth-identity's {@code KeyListItem} wire shape. There is deliberately NO secret / hash field
     * here — the upstream does not emit one and this client must not be able to carry one.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WireListItem(
            String keyId,
            String prefix,
            String environment,
            Instant createdAt,
            String status,
            Instant expiresAt) {

        ApiKeyView toView(String queriedEnvironment) {
            return new ApiKeyView(
                    keyId,
                    // name: no column in api_keys (V002) and no other owner -> honestly absent.
                    null,
                    prefix,
                    // scopes: not modelled on an api_keys row -> honestly empty, not invented.
                    List.of(),
                    createdAt,
                    // lastUsedAt: api_keys has no last_used_at column -> honestly absent.
                    null,
                    status,
                    environment == null ? queriedEnvironment : environment,
                    expiresAt);
        }
    }
}
