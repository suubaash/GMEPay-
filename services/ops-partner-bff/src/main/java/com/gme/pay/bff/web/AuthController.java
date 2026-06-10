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
 * <p><b>REPLACE WITH auth-identity integration in Phase 4 hardening.</b> The real
 * implementation will delegate to {@code auth-identity}'s OAuth2/JWT issuer,
 * apply RBAC, and rotate refresh tokens. Today the BFF just returns a
 * deterministic mock JWT-shaped string so the UI can store-and-forward a token
 * across pages.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST /v1/auth/login}   — body {@code {username, password}} → mock JWT or 401
 *   <li>{@code POST /v1/auth/refresh} — body {@code {token}}              → regenerated mock JWT
 * </ul>
 */
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
