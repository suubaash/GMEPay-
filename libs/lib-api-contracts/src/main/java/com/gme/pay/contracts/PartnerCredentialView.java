package com.gme.pay.contracts;

import java.time.Instant;

/**
 * Read shape of one {@code partner_credential} ledger row (V028) — Slice 8
 * Lane B. Returned by {@code GET /v1/admin/partners/{code}/credentials}.
 *
 * <p><b>NEVER carries secret material.</b> The view is the display-safe
 * residue of an issued credential: the auth-identity public key identifier,
 * the prefix and the last 4 characters. The one-time plaintext rides
 * {@link IssuedCredentialBundle} on the issuance/rotation response only and
 * is unrecoverable afterwards (auth-identity stores a salted hash, this
 * service stores nothing).
 *
 * <ul>
 *   <li>{@code environment} — {@code SANDBOX} | {@code PRODUCTION}.</li>
 *   <li>{@code credentialKind} — {@code API_KEY} | {@code HMAC_SECRET} |
 *       {@code WEBHOOK_SECRET} (the V028 CHECK rosters).</li>
 *   <li>{@code status} — {@code ACTIVE} | {@code ROTATED} | {@code REVOKED} |
 *       {@code EXPIRED}.</li>
 * </ul>
 */
public record PartnerCredentialView(
        Long id,
        String environment,
        String credentialKind,
        String authIdentityKeyId,
        String prefix,
        String last4,
        Instant issuedAt,
        Instant expiresAt,
        Instant rotatedAt,
        Instant revokedAt,
        String status) {
}
