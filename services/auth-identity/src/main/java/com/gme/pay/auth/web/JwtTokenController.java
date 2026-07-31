package com.gme.pay.auth.web;

import com.gme.pay.auth.dto.IssueTokenRequest;
import com.gme.pay.auth.dto.IssueTokenResponse;
import com.gme.pay.auth.dto.JwtKeySetStatusResponse;
import com.gme.pay.auth.dto.VerifyTokenRequest;
import com.gme.pay.auth.dto.VerifyTokenResponse;
import com.gme.pay.auth.service.JwtTokenService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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

    /**
     * {@code GET /internal/auth/token/keys} — the JWT key set this process is running with (T0-6).
     *
     * <p>Rotation is only an operation if it can be verified, and "restart the pods and hope"
     * is not verification. This returns the active {@code kid} plus each still-accepted
     * predecessor and the instant it becomes safe to remove, read from the live signing helper
     * rather than from configuration, so a replica that did not pick up the new key set is visible
     * as a different {@code activeKid}.
     *
     * <p>No key material is returned — a {@code kid} is a one-way thumbprint already present in
     * the header of every token minted. The route sits under {@code /internal/auth} and is
     * therefore behind the {@code X-Gme-Internal} gate along with everything else this service
     * exposes.
     */
    @GetMapping("/keys")
    public ResponseEntity<JwtKeySetStatusResponse> keys() {
        return ResponseEntity.ok(tokenService.keySetStatus());
    }
}
