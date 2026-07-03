package com.gme.pay.auth.dto;

import java.time.Instant;

/**
 * Internal API response body for {@code POST /internal/v1/keys} (Slice 8
 * Lane B).
 *
 * <h2>⚠ SECURITY (SEC-09 §4)</h2>
 *
 * <p>{@code secretPlaintext} is the ONE-TIME plaintext: by the time this
 * record exists the service has already discarded the plaintext in favour of
 * the salted PBKDF2 hash ({@code api_keys.secret_hash}). This record MUST
 * NEVER be logged or persisted — {@link #toString()} is overridden to redact
 * the secret so a stray logger call leaks nothing; do not "fix" that
 * override.
 *
 * @param keyId           the public key identifier ({@code api_keys.api_key})
 *                        — safe to display and persist.
 * @param secretPlaintext the one-time plaintext secret. Forward once, drop.
 * @param prefix          non-secret display prefix of the key id (safe to
 *                        show/persist). Lets a self-serve caller (ops-partner-bff
 *                        Get-Started flow) render the key without re-deriving it.
 * @param environment     {@code SANDBOX | PRODUCTION} the credential was issued
 *                        under (echoed from the request) — so a sandbox key is
 *                        visibly scoped and never mistaken for a production one.
 * @param createdAt       issuance instant recorded on the {@code api_keys} row.
 * @param expiresAt       hard expiry as persisted; {@code null} = never.
 */
public record IssueKeyResponse(
        String keyId,
        String secretPlaintext,
        String prefix,
        String environment,
        Instant createdAt,
        Instant expiresAt) {

    /** Redacting override (SEC-09 §4) — see the class Javadoc. */
    @Override
    public String toString() {
        return "IssueKeyResponse[keyId=" + keyId
                + ", secretPlaintext=REDACTED, prefix=" + prefix
                + ", environment=" + environment + ", createdAt=" + createdAt
                + ", expiresAt=" + expiresAt + "]";
    }
}
