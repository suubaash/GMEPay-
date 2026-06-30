package com.gme.pay.auth.dto;

/**
 * Response body for {@code POST /internal/auth/token/verify}.
 *
 * <p>Mirrors the {@link VerifyResponse} convention used by the HMAC verify
 * endpoint: {@code 200 OK} for both outcomes, {@code valid} carries the
 * decision so the caller maps to its own HTTP status toward the upstream actor.
 *
 * @param valid     true when the token signature is valid and the token is not expired.
 * @param subject   the {@code sub} claim when valid; {@code null} otherwise.
 * @param jti       the token id ({@code jti} claim) when valid; {@code null} otherwise.
 * @param expiresAt the {@code exp} claim (epoch seconds) when valid; {@code 0} otherwise.
 * @param errorCode failure reason when invalid (e.g. {@code INVALID_TOKEN},
 *                  {@code EXPIRED_TOKEN}); {@code null} when valid.
 */
public record VerifyTokenResponse(
        boolean valid,
        String subject,
        String jti,
        long expiresAt,
        String errorCode) {

    public static VerifyTokenResponse ok(String subject, String jti, long expiresAt) {
        return new VerifyTokenResponse(true, subject, jti, expiresAt, null);
    }

    public static VerifyTokenResponse fail(String errorCode) {
        return new VerifyTokenResponse(false, null, null, 0L, errorCode);
    }
}
