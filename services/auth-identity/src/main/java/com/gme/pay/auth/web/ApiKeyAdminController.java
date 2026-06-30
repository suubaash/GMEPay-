package com.gme.pay.auth.web;

import com.gme.pay.auth.dto.CredentialLookupRequest;
import com.gme.pay.auth.dto.CredentialLookupResponse;
import com.gme.pay.auth.dto.IssueKeyRequest;
import com.gme.pay.auth.dto.IssueKeyResponse;
import com.gme.pay.auth.service.ApiKeyIssuanceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal API endpoints for machine-credential lifecycle (Slice 8 Lane B):
 *
 * <ul>
 *   <li>{@code POST /internal/auth/keys} — issue one credential (API key +
 *       HMAC secret pair, or webhook signing secret). Returns the ONE-TIME
 *       plaintext; only the salted hash is stored (SEC-09 §4).</li>
 *   <li>{@code POST /internal/auth/keys/{keyId}/revoke} — revoke by public
 *       key identifier; idempotent.</li>
 *   <li>{@code POST /internal/auth/keys/rotate} — revoke the principal's active
 *       keys and issue a fresh replacement (returns the new one-time plaintext).</li>
 *   <li>{@code POST /internal/auth/keys/resolve} — DB-backed lookup the
 *       api-gateway uses to validate an {@code X-API-Key} (no secret returned).</li>
 * </ul>
 *
 * <p>Mounted under {@code /internal/auth} — the machine-credential surface
 * pinned by {@code WebSurfaceScopeTest} (ADR-011).
 *
 * <p>Intended for config-registry only (internal network, not publicly
 * routed) — the same trust posture as {@link AuthVerifyController}. The
 * caller drives issuance from its partner-lifecycle transitions and ledgers
 * the returned key id; the plaintext rides through to the activation
 * response and is never persisted anywhere.
 */
@RestController
@RequestMapping("/internal/auth/keys")
public class ApiKeyAdminController {

    private final ApiKeyIssuanceService issuanceService;

    public ApiKeyAdminController(ApiKeyIssuanceService issuanceService) {
        this.issuanceService = issuanceService;
    }

    @PostMapping
    public ResponseEntity<IssueKeyResponse> issue(@RequestBody IssueKeyRequest request) {
        return ResponseEntity.ok(issuanceService.issue(request));
    }

    @PostMapping("/{keyId}/revoke")
    public ResponseEntity<Void> revoke(@PathVariable("keyId") String keyId) {
        issuanceService.revoke(keyId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/rotate")
    public ResponseEntity<IssueKeyResponse> rotate(@RequestBody IssueKeyRequest request) {
        return ResponseEntity.ok(issuanceService.rotate(request));
    }

    @PostMapping("/resolve")
    public ResponseEntity<CredentialLookupResponse> resolve(
            @RequestBody CredentialLookupRequest request) {
        String apiKey = request == null ? null : request.apiKey();
        return ResponseEntity.ok(issuanceService.resolve(apiKey));
    }
}
