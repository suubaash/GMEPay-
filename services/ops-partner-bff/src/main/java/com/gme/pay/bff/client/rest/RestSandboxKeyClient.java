package com.gme.pay.bff.client.rest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.gme.pay.bff.client.SandboxKeyClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Production {@link SandboxKeyClient}. Issues REAL sandbox credentials from
 * auth-identity (which owns key lifecycle, ADR-011) instead of the in-memory
 * {@link com.gme.pay.bff.client.stub.StubSandboxKeyClient}. Active when
 * {@code gmepay.auth-identity.client=rest}; otherwise the stub wins so the BFF
 * still boots standalone for tests / local dev (the stub is
 * {@code matchIfMissing=true}).
 *
 * <p>Endpoint mapping (auth-identity {@code ApiKeyAdminController}, all under
 * the {@code /internal/auth/keys} machine surface — ADR-011):
 * <ul>
 *   <li>issue -> {@code POST /internal/auth/keys} with {@code environment=SANDBOX},
 *       {@code purpose=API}, {@code pk_test_}/{@code sk_test_} prefixes;</li>
 *   <li>list  -> {@code GET /internal/auth/keys?partnerId=&environment=SANDBOX}.</li>
 * </ul>
 *
 * <h2>SANDBOX scoping (security-critical)</h2>
 *
 * <p>Every credential minted here is pinned to {@code environment=SANDBOX} on
 * the issue request and every list query filters {@code environment=SANDBOX} —
 * so a key issued through the self-serve portal is stored under auth-identity's
 * {@code partner:{code}:SANDBOX} principal, carries the {@code pk_test_}/
 * {@code sk_test_} test prefixes, and can NEVER authorize a production /
 * real-money call. The portal contract's {@code scope} field is always the
 * constant {@code "SANDBOX"} (auth-identity's {@code environment} echoed back).
 * This client never sends {@code PRODUCTION} and never touches the 4-eyes /
 * rotation production path.
 *
 * <h2>SEC-09 §4 (one-time plaintext)</h2>
 *
 * <p>{@code POST} returns the one-time plaintext once; auth-identity has
 * already discarded it in favour of a salted hash. The list surface returns no
 * secret material. This client never logs the plaintext.
 */
@Component
@Primary
@ConditionalOnProperty(name = "gmepay.auth-identity.client", havingValue = "rest")
public class RestSandboxKeyClient implements SandboxKeyClient {

    private static final Logger log = LoggerFactory.getLogger(RestSandboxKeyClient.class);

    /** SANDBOX scope marker — sandbox keys must not authorize production calls. */
    static final String SCOPE_SANDBOX = "SANDBOX";

    /** Public key-id prefix (test = sandbox), mirrors auth-identity's pk_test_. */
    static final String KEY_PREFIX = "pk_test_";

    /** Secret prefix (test = sandbox), mirrors auth-identity's sk_test_. */
    static final String SECRET_PREFIX = "sk_test_";

    /** API purpose (key id + HMAC signing secret pair), not WEBHOOK. */
    static final String PURPOSE_API = "API";

    private final RestClient restClient;

    @Autowired
    public RestSandboxKeyClient(
            @Value("${gmepay.auth-identity.base-url:http://auth-identity:8080}") String baseUrl) {
        this(RestClient.builder().baseUrl(baseUrl).build());
    }

    /** Package-private constructor for tests to inject a pre-built RestClient. */
    RestSandboxKeyClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public IssuedSandboxKey issue(String partnerId, String name) {
        // partnerId is the numeric surrogate + business code auth-identity keys
        // the credential to. The BFF path variable is a string; forward it as
        // BOTH the numeric partnerId (when it parses) and the partnerCode seed
        // for the partner:{code}:SANDBOX principal username.
        Long numericPartner = parseLongOrNull(partnerId);
        String partnerCode = partnerId == null || partnerId.isBlank() ? "anon" : partnerId.trim();

        Map<String, Object> body = new HashMap<>();
        body.put("partnerId", numericPartner);
        body.put("partnerCode", partnerCode);
        body.put("environment", SCOPE_SANDBOX);
        body.put("purpose", PURPOSE_API);
        body.put("keyPrefix", KEY_PREFIX);
        body.put("secretPrefix", SECRET_PREFIX);
        // name is a portal-side human label; auth-identity's issue contract has
        // no name field, so it is not forwarded (the label is a UI concern).

        try {
            WireIssued issued = restClient.post()
                    .uri("/internal/auth/keys")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(WireIssued.class);
            if (issued == null) {
                throw new ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_GATEWAY,
                        "auth-identity returned no body issuing a sandbox key");
            }
            // Map auth-identity's environment -> the portal's scope. Guard the
            // scope so a sandbox key is always labelled SANDBOX downstream.
            String scope = issued.environment() == null ? SCOPE_SANDBOX : issued.environment();
            return new IssuedSandboxKey(
                    issued.keyId(), issued.secretPlaintext(), issued.prefix(), scope,
                    issued.createdAt());
        } catch (RestClientResponseException e) {
            log.warn("auth-identity error issuing sandbox key for partner {} (status={})",
                    partnerCode, e.getStatusCode());
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatusCode.valueOf(e.getStatusCode().value()),
                    "auth-identity rejected sandbox key issuance");
        } catch (ResourceAccessException e) {
            log.warn("auth-identity unreachable issuing sandbox key for partner {}: {}",
                    partnerCode, e.getMessage());
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_GATEWAY,
                    "auth-identity unreachable");
        }
    }

    @Override
    public List<SandboxKeyView> listForPartner(String partnerId) {
        Long numericPartner = parseLongOrNull(partnerId);
        if (numericPartner == null) {
            // No numeric partner id -> auth-identity keys hang off a numeric
            // partner surrogate, so there is nothing to list. Empty (never null).
            return List.of();
        }
        try {
            String uri = UriComponentsBuilder.fromPath("/internal/auth/keys")
                    .queryParam("partnerId", numericPartner)
                    .queryParam("environment", SCOPE_SANDBOX)
                    .build().toUriString();
            List<WireListItem> rows = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<WireListItem>>() {});
            if (rows == null) {
                return List.of();
            }
            return rows.stream()
                    .map(r -> new SandboxKeyView(
                            r.keyId(), r.prefix(),
                            r.environment() == null ? SCOPE_SANDBOX : r.environment(),
                            r.createdAt()))
                    .toList();
        } catch (RestClientResponseException e) {
            log.warn("auth-identity error listing sandbox keys for partner {} (status={})",
                    numericPartner, e.getStatusCode());
            return List.of();
        } catch (ResourceAccessException e) {
            log.warn("auth-identity unreachable listing sandbox keys for partner {}: {}",
                    numericPartner, e.getMessage());
            return List.of();
        }
    }

    private static Long parseLongOrNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** auth-identity's {@code IssueKeyResponse} wire shape (subset we surface). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WireIssued(
            String keyId,
            String secretPlaintext,
            String prefix,
            String environment,
            Instant createdAt,
            Instant expiresAt) {
    }

    /** auth-identity's {@code KeyListItem} wire shape. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WireListItem(
            String keyId,
            String prefix,
            String environment,
            Instant createdAt) {
    }
}
