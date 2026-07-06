package com.gme.pay.auth.web;

import com.gme.pay.auth.dto.HumanLoginRequest;
import com.gme.pay.auth.dto.HumanLoginResponse;
import com.gme.pay.auth.service.HumanLoginService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Human operator password login (real-auth slice).
 *
 * <ul>
 *   <li>{@code POST /v1/auth/login} — body {@code {username, password}} →
 *       {@code {token, expiresAt, tokenType, username, roles}} with a REAL
 *       HS256 JWT carrying {@code preferred_username} + {@code roles} claims,
 *       or {@code 401} (standard Spring error body) on any failure.</li>
 * </ul>
 *
 * <p>Relationship to ADR-011: Keycloak remains the production human IdP for
 * the SPA OIDC/PKCE path. This endpoint is the <em>local-credential</em> login
 * that ops-partner-bff's {@code /v1/auth/login} proxies to, replacing the
 * BFF's retired {@code password=demo} stub with a database-backed PBKDF2
 * credential and a genuinely signed token. It is the single, deliberate
 * exemption in {@code WebSurfaceScopeTest}'s machine-surface guard.
 *
 * <p>All failure modes (unknown user, wrong password, non-ACTIVE principal,
 * principal without a password) return an identical 401 — see
 * {@link HumanLoginService} for the timing-uniformity notes.
 */
@RestController
@RequestMapping("/v1/auth")
public class HumanLoginController {

    private final HumanLoginService loginService;

    public HumanLoginController(HumanLoginService loginService) {
        this.loginService = loginService;
    }

    @PostMapping("/login")
    public ResponseEntity<HumanLoginResponse> login(@RequestBody HumanLoginRequest request) {
        String username = request == null ? null : request.username();
        String password = request == null ? null : request.password();
        return ResponseEntity.ok(loginService.login(username, password));
    }
}
