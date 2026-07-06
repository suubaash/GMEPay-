package com.gme.pay.bff.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.gme.pay.bff.web.dto.LoginRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Real login proxy (real-auth slice): {@code POST /v1/auth/login} forwards the
 * {@code {username, password}} pair to auth-identity's human login endpoint
 * ({@code POST /v1/auth/login}, see {@code HumanLoginController}) and returns
 * auth-identity's response <em>verbatim</em> — a genuinely signed HS256 JWT
 * with {@code preferred_username} + {@code roles} claims.
 *
 * <p>This replaces the Phase-1 {@code password=demo} stub that minted
 * {@code mock.eyJ…} tokens. There is no demo password and no local token
 * minting left in this class: if auth-identity rejects the credentials the
 * caller gets 401, and if auth-identity is unreachable the caller gets 503 —
 * never a fabricated token. The legacy {@code POST /v1/auth/refresh} stub
 * (which could only regenerate fake tokens, and had no remaining UI callers)
 * is removed outright; expired sessions re-authenticate.
 *
 * <p>Base URL comes from {@code gmepay.auth-identity.base-url}, the same
 * property the other auth-identity clients use ({@code RestRbacAdminClient} et
 * al.). {@code /v1/auth/login} is outside auth-identity's internal-auth gate
 * (it is the one human-facing exemption), so no {@code X-Gme-Internal} header
 * is attached.
 *
 * <p>Keycloak (ADR-011) remains the production OIDC path for the SPAs; this
 * endpoint backs the dev/password form behind
 * {@code NEXT_PUBLIC_ALLOW_DEV_LOGIN=true}.
 */
@RestController
@RequestMapping("/v1/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final RestClient restClient;

    @Autowired
    public AuthController(
            RestClient.Builder builder,
            @Value("${gmepay.auth-identity.base-url:http://auth-identity:8080}") String baseUrl) {
        this(builder.baseUrl(baseUrl).build());
    }

    /** Package-private constructor for tests to inject a pre-built RestClient. */
    AuthController(RestClient restClient) {
        this.restClient = restClient;
    }

    @PostMapping("/login")
    public JsonNode login(@RequestBody LoginRequest body) {
        String username = body == null ? null : body.username();
        String password = body == null ? null : body.password();
        if (username == null || username.isBlank() || password == null || password.isEmpty()) {
            // Cheap local reject — auth-identity would 401 this anyway.
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid credentials");
        }
        try {
            return restClient.post()
                    .uri("/v1/auth/login")
                    .body(Map.of("username", username, "password", password))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException upstream) {
            if (upstream.getStatusCode().value() == HttpStatus.UNAUTHORIZED.value()) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid credentials");
            }
            log.warn("auth-identity login returned {}: {}",
                    upstream.getStatusCode().value(), upstream.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "auth-identity error (" + upstream.getStatusCode().value() + ")");
        } catch (ResourceAccessException network) {
            log.warn("auth-identity unreachable for login: {}", network.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "auth-identity unavailable");
        }
    }
}
