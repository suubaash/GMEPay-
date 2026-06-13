package com.gme.pay.bff.web;

import com.gme.pay.bff.web.dto.LoginRequest;
import com.gme.pay.bff.web.dto.LoginResponse;
import com.gme.pay.bff.web.dto.RefreshRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

/**
 * Phase-1 stub auth controller for the Admin UI / Partner Portal login flow.
 *
 * <h2>Slice 1 status: DEPRECATED — scheduled for removal</h2>
 *
 * <p>Per ADR-011 ("Keycloak for humans, auth-identity for machines"), human
 * authentication moves to Keycloak (realm {@code gmepay}) accessed directly by
 * the SPAs via the OIDC Authorization Code + PKCE flow. The api-gateway
 * (companion change in {@code services/api-gateway/.../SecurityConfig.java})
 * is now an OAuth2 resource server that validates the Keycloak-issued JWT and
 * maps the realm role {@code OPERATOR} to Spring authority {@code ROLE_OPERATOR}.
 *
 * <p><b>Why this class still exists today:</b> the admin-ui swap to Keycloak
 * OIDC ships in Slice 1's UI ticket (1D.3). Until that lands the SPA still
 * POSTs {@code {username, password}} to {@code /v1/auth/login} and stores the
 * returned token. To avoid a broken-build window we keep the endpoint live and
 * returning the same mock JWT shape it always did — the new resource-server
 * config in api-gateway does not yet front this BFF for admin traffic, so the
 * legacy flow continues to work for that brief overlap.
 *
 * <p><b>Migration path / removal plan:</b>
 * <ol>
 *   <li>1D.3 swaps admin-ui to {@code @react-keycloak/web} (or equivalent),
 *       redirecting to Keycloak for login and attaching the resulting JWT as
 *       {@code Authorization: Bearer ...} on outbound calls.</li>
 *   <li>The same ticket removes the admin-ui code that calls
 *       {@code POST /v1/auth/login} and {@code POST /v1/auth/refresh}.</li>
 *   <li>Slice 1's exit gate verifies "no {@code password=demo} left" in the UI;
 *       once that ships, this controller plus {@link com.gme.pay.bff.web.dto.LoginRequest},
 *       {@link com.gme.pay.bff.web.dto.LoginResponse}, {@link com.gme.pay.bff.web.dto.RefreshRequest},
 *       and the associated test {@code AuthControllerTest} are deleted in a
 *       follow-up commit referenced as 1C.4-cleanup.</li>
 * </ol>
 *
 * <p>Endpoints (kept identical for the transition window):
 * <ul>
 *   <li>{@code POST /v1/auth/login}   — body {@code {username, password}} → mock JWT or 401
 *   <li>{@code POST /v1/auth/refresh} — body {@code {token}}              → regenerated mock JWT
 * </ul>
 *
 * @deprecated Replaced by direct Keycloak OIDC login from the SPAs (ADR-011).
 *     Remove after Slice 1's admin-ui auth swap (ticket 1D.3) lands.
 */
@Deprecated(since = "Slice 1", forRemoval = true)
@RestController
@RequestMapping("/v1/auth")
public class AuthController {

    /** Default token lifetime for the Phase-1 stub. */
    static final long TOKEN_TTL_SECONDS = 3600;

    /** Phase-1 demo password. Anything else (or empty) yields 401. */
    static final String DEMO_PASSWORD = "demo";

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest body) {
        String username = body == null ? null : body.username();
        String password = body == null ? null : body.password();
        if (username == null || username.isBlank()
                || password == null || !DEMO_PASSWORD.equals(password)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "invalid credentials");
        }
        return issue(username, "ADMIN");
    }

    @PostMapping("/refresh")
    public LoginResponse refresh(@RequestBody RefreshRequest body) {
        String token = body == null ? null : body.token();
        if (token == null || token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "invalid token");
        }
        // Phase-1 stub: any non-empty token regenerates. The "subject" inside the
        // mock token is opaque to us; we mint a fresh one tagged "refresh".
        return issue("refresh", "ADMIN");
    }

    /**
     * Builds a deterministic JWT-shaped string {@code mock.eyJ...} so the UI
     * code path that decodes the middle segment continues to work without
     * pulling in a real JWT library.
     */
    private static LoginResponse issue(String subject, String role) {
        Instant expiresAt = Instant.now().plus(TOKEN_TTL_SECONDS, ChronoUnit.SECONDS);
        String payload = "{\"sub\":\"" + subject + "\",\"role\":\"" + role
                + "\",\"exp\":" + expiresAt.getEpochSecond() + "}";
        String b64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return new LoginResponse("mock.eyJ" + b64, expiresAt, role);
    }
}
