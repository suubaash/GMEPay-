package com.gme.pay.auth.web;

import com.gme.pay.auth.dto.IssueTokenRequest;
import com.gme.pay.auth.dto.IssueTokenResponse;
import com.gme.pay.auth.dto.VerifyTokenRequest;
import com.gme.pay.auth.dto.VerifyTokenResponse;
import com.gme.pay.auth.service.JwtTokenService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal API: HS256 service-to-service token issuance + verification.
 *
 * <ul>
 *   <li>{@code POST /internal/auth/token/issue} — mint a short-lived signed
 *       capability token for an internal subject + claims.</li>
 *   <li>{@code POST /internal/auth/token/verify} — validate a token's signature
 *       and expiry, returning the decoded {@code sub}/{@code jti}/{@code exp}.</li>
 * </ul>
 *
 * <p>Mounted under {@code /internal/auth} — the machine surface pinned by
 * {@link WebSurfaceScopeTest} (ADR-011). NOT a human-operator login surface;
 * operator sessions are owned by Keycloak.
 *
 * <p>Like {@link AuthVerifyController}, verify returns {@code 200 OK} for both
 * accept and reject so the consuming gateway maps the {@code valid}/{@code errorCode}
 * pair to its own HTTP status toward the upstream actor.
 */
@RestController
@RequestMapping("/internal/auth/token")
public class JwtTokenController {

    private final JwtTokenService tokenService;

    public JwtTokenController(JwtTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @PostMapping("/issue")
    public ResponseEntity<IssueTokenResponse> issue(@RequestBody IssueTokenRequest request) {
        return ResponseEntity.ok(tokenService.issue(request));
    }

    @PostMapping("/verify")
    public ResponseEntity<VerifyTokenResponse> verify(@RequestBody VerifyTokenRequest request) {
        String token = request == null ? null : request.token();
        return ResponseEntity.ok(tokenService.verify(token));
    }
}
