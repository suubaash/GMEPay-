package com.gme.pay.auth.dto;

/**
 * Request body for {@code POST /internal/auth/token/verify}.
 *
 * @param token the compact JWT to validate (signature + expiry).
 */
public record VerifyTokenRequest(String token) {

    /** Redact the token from any accidental log line. */
    @Override
    public String toString() {
        return "VerifyTokenRequest[token=REDACTED]";
    }
}
