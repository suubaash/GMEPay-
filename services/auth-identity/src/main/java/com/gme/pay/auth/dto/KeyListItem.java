package com.gme.pay.auth.dto;

import java.time.Instant;

/**
 * Internal API list-view row for {@code GET /internal/auth/keys} (Slice 8
 * Lane B — self-serve read-back). Carries only NON-secret metadata: the public
 * key identifier, its display prefix, the environment it was issued under and
 * the issuance instant.
 *
 * <h2>⚠ SECURITY (SEC-09 §4)</h2>
 *
 * <p>There is intentionally NO secret field on this record. The plaintext
 * secret is shown exactly once at issuance ({@link IssueKeyResponse}) and is
 * unrecoverable afterwards — the list surface must never re-expose it.
 *
 * @param keyId       the public key identifier ({@code api_keys.api_key}).
 * @param prefix      non-secret display prefix of the key id.
 * @param environment {@code SANDBOX | PRODUCTION} the credential belongs to.
 * @param createdAt   issuance instant recorded on the {@code api_keys} row.
 * @param status      lifecycle state from {@code api_keys.status}:
 *                    {@code ACTIVE | PENDING_EXPIRY | REVOKED} (V002 roster). Added for
 *                    gap T1-3 so the Partner Portal's API Keys page can show whether a
 *                    key still works instead of a fabricated {@code PRIMARY}/
 *                    {@code ROTATING} label. Non-secret.
 * @param expiresAt   expiry instant from {@code api_keys.expires_at}; {@code null} = no
 *                    expiry configured.
 */
public record KeyListItem(
        String keyId,
        String prefix,
        String environment,
        Instant createdAt,
        String status,
        Instant expiresAt) {
}
