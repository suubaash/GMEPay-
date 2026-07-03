package com.gme.pay.bff.client;

import java.time.Instant;
import java.util.List;

/**
 * Self-serve SANDBOX API-key issuance for the Partner Portal "Get Started"
 * flow. A logged-in partner generates a sandbox credential themselves — no
 * account-manager / 4-eyes step (that gate is reserved for PRODUCTION keys,
 * which auth-identity's {@code /internal/auth/keys} + config-registry rotation
 * workflow own and which this client never touches).
 *
 * <p>SECURITY (mirrors auth-identity SEC-09 §4): issuance returns the ONE-TIME
 * plaintext {@code apiKey} exactly once. The backing store keeps only a salted
 * one-way hash of the secret; the plaintext is unrecoverable afterwards.
 *
 * <p>SCOPE: every key minted here is {@code SANDBOX}-scoped — it must NOT
 * authorize real-money production calls. The scope rides on both the issue
 * response and the list view so the portal can label it and downstream
 * enforcement can refuse a sandbox key on a production route.
 *
 * <p>Production implementation calls auth-identity
 * ({@code POST /internal/auth/keys} with {@code environment=SANDBOX},
 * {@code purpose=API}, {@code pk_test_/sk_test_} prefixes) so the real
 * {@code SecretHasher} + {@code api_keys} store issue the credential. Phase-1
 * default is an in-memory stub that reproduces the same one-time-plaintext /
 * hash-only / SANDBOX-scope contract without booting auth-identity.
 */
public interface SandboxKeyClient {

    /**
     * Issues a fresh SANDBOX API key for {@code partnerId}. The returned
     * {@link IssuedSandboxKey#apiKey()} is the one-time plaintext secret — it is
     * never returned again by {@link #listForPartner(String)}.
     *
     * @param partnerId the authenticated partner the key is scoped to
     * @param name      optional human label for the key (may be null/blank)
     */
    IssuedSandboxKey issue(String partnerId, String name);

    /**
     * Lists the SANDBOX keys already minted for {@code partnerId}, newest-first.
     * Never returns plaintext secrets — only id, prefix, createdAt, scope.
     * Returns an empty list (never null) for a partner with no sandbox keys.
     */
    List<SandboxKeyView> listForPartner(String partnerId);

    /**
     * Issue response — carries the ONE-TIME plaintext {@code apiKey}. This
     * record MUST NOT be logged or persisted; {@link #toString()} redacts the
     * secret so a stray logger call leaks nothing.
     *
     * @param keyId     public key identifier (safe to display/persist)
     * @param apiKey    one-time plaintext secret — show once, then drop
     * @param prefix    non-secret display prefix of the key id
     * @param scope     always {@code "SANDBOX"}
     * @param createdAt issuance instant
     */
    record IssuedSandboxKey(
            String keyId,
            String apiKey,
            String prefix,
            String scope,
            Instant createdAt) {

        @Override
        public String toString() {
            return "IssuedSandboxKey[keyId=" + keyId
                    + ", apiKey=REDACTED, prefix=" + prefix
                    + ", scope=" + scope + ", createdAt=" + createdAt + "]";
        }
    }

    /**
     * List view of an already-issued sandbox key. No secret material.
     *
     * @param keyId     public key identifier
     * @param prefix    non-secret display prefix
     * @param scope     always {@code "SANDBOX"}
     * @param createdAt issuance instant
     */
    record SandboxKeyView(
            String keyId,
            String prefix,
            String scope,
            Instant createdAt) {}
}
