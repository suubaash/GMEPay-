package com.gme.pay.registry.client;

import java.time.Instant;

/**
 * Port — collaborator interface for issuing / revoking machine credentials in
 * the auth-identity service (Slice 8 Lane B — Credentials).
 *
 * <p>The production implementation
 * ({@link com.gme.pay.registry.client.rest.RestAuthIdentityClient}) calls
 * auth-identity's internal issuance API ({@code POST /internal/auth/keys})
 * over the internal network. Local dev / unit slices fall back to
 * {@link StubAuthIdentityClient}, which mints random material in-process —
 * same conditional seam as {@code KybScreeningClient} / {@code RestKybClient}.
 *
 * <p>Per MSA rules (INTER_SERVICE_CONTRACTS.md): config-registry never reads
 * auth-identity's database; the salted secret hashes live there, the
 * plaintext crosses this port EXACTLY ONCE on the issuance response and is
 * never persisted on this side (SEC-09 §4).
 */
public interface AuthIdentityClient {

    /**
     * Issue one credential (an api_keys row in auth-identity: public key
     * identifier + salted-hashed secret).
     *
     * @return the public key identifier plus the ONE-TIME plaintext secret.
     */
    IssuedKey issueKey(IssueKeyCommand command);

    /**
     * Revoke a previously issued credential by its public key identifier.
     * Idempotent — revoking an unknown/already-revoked key is a no-op.
     */
    void revokeKey(String keyId);

    /**
     * What to issue.
     *
     * @param partnerId    the registry's partner surrogate id (auth-identity
     *                     links its PARTNER principal to it).
     * @param partnerCode  human-facing business code (principal username seed).
     * @param environment  SANDBOX | PRODUCTION.
     * @param purpose      API (key + HMAC signing secret pair) | WEBHOOK
     *                     (webhook signing secret).
     * @param keyPrefix    prefix for the public key identifier (e.g. {@code pk_test_}).
     * @param secretPrefix prefix for the plaintext secret (e.g. {@code sk_test_}).
     * @param expiresAt    when the credential expires (the 11-month rotation
     *                     scheduler proposes ahead of this).
     */
    record IssueKeyCommand(
            Long partnerId,
            String partnerCode,
            String environment,
            String purpose,
            String keyPrefix,
            String secretPrefix,
            Instant expiresAt) {
    }

    /**
     * What auth-identity issued. {@code secretPlaintext} is the ONE-TIME
     * plaintext — auth-identity has already discarded it in favour of the
     * salted hash; the caller forwards it once and drops it. NEVER log or
     * persist this record ({@code toString()} redacts the secret).
     *
     * @param keyId           the public key identifier (api_keys.api_key) —
     *                        safe to display/persist.
     * @param secretPlaintext the one-time plaintext secret.
     * @param expiresAt       expiry as recorded by auth-identity.
     */
    record IssuedKey(String keyId, String secretPlaintext, Instant expiresAt) {

        /** Redacting override so a stray logger call leaks nothing (SEC-09 §4). */
        @Override
        public String toString() {
            return "IssuedKey[keyId=" + keyId + ", secretPlaintext=REDACTED,"
                    + " expiresAt=" + expiresAt + "]";
        }
    }
}
