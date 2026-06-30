package com.gme.pay.auth.dto;

/**
 * Response body for {@code POST /internal/auth/keys/resolve}.
 *
 * <p>The DB-backed partner-credential lookup the api-gateway consumes to decide
 * whether an inbound {@code X-API-Key} is a known, active, non-expired credential
 * <em>issued by this service</em> (rows in the local {@code api_keys} table).
 *
 * <p>SECURITY: this response NEVER carries secret material. {@code api_keys}
 * stores only a salted PBKDF2 hash of the secret (SEC-09 §4) — the plaintext
 * HMAC secret is unrecoverable. The gateway uses this lookup to validate key
 * identity + lifecycle; HMAC <em>signature</em> verification is delegated to
 * {@code POST /internal/auth/verify} (which has the secret source wired in).
 *
 * @param found     true when an {@code api_keys} row exists for the api key.
 * @param active    true when that row's status is ACTIVE and it is not expired.
 * @param partnerId the owning partner surrogate id (from the PARTNER principal);
 *                  {@code null} when not found or the principal carries none.
 */
public record CredentialLookupResponse(
        boolean found,
        boolean active,
        Long partnerId) {

    /** Lookup miss — unknown api key. */
    public static CredentialLookupResponse notFound() {
        return new CredentialLookupResponse(false, false, null);
    }
}
