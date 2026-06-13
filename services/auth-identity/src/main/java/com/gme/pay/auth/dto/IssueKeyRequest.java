package com.gme.pay.auth.dto;

import java.time.Instant;

/**
 * Internal API request body for {@code POST /internal/v1/keys} (Slice 8
 * Lane B). Local to this service per the {@link VerifyRequest} precedent —
 * the internal issuance contract is owned by auth-identity, and
 * config-registry's {@code RestAuthIdentityClient} mirrors the shape (MSA
 * rule 5: service-owned models stay private; cross-service DTO sharing is
 * for the public lib-api-contracts surface only).
 *
 * @param partnerId    config-registry's partner surrogate id, linked onto the
 *                     PARTNER principal.
 * @param partnerCode  human-facing business code (principal username seed).
 * @param environment  SANDBOX | PRODUCTION.
 * @param purpose      API (key + HMAC signing secret) | WEBHOOK (payload
 *                     signing secret).
 * @param keyPrefix    prefix for the public key identifier (e.g. {@code pk_test_}).
 * @param secretPrefix prefix for the plaintext secret (e.g. {@code sk_test_}).
 * @param expiresAt    hard expiry of the issued material; {@code null} = never.
 */
public record IssueKeyRequest(
        Long partnerId,
        String partnerCode,
        String environment,
        String purpose,
        String keyPrefix,
        String secretPrefix,
        Instant expiresAt) {
}
