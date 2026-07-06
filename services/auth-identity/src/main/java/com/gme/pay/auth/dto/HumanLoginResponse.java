package com.gme.pay.auth.dto;

import java.util.List;

/**
 * Response body for {@code POST /v1/auth/login} (human operator password login).
 *
 * <p>{@code token} is a REAL compact HS256 JWT (header.payload.signature) minted
 * by {@link com.gme.pay.auth.domain.JwtHelper} with {@code preferred_username}
 * and {@code roles} claims. Field names {@code token} / {@code expiresAt} keep
 * the wire shape the SPAs already consume from the legacy BFF stub.
 *
 * @param token     compact serialized HS256 JWT.
 * @param expiresAt epoch-second expiry ({@code exp} claim).
 * @param tokenType always {@code "Bearer"}.
 * @param username  authenticated principal's username (mirrors {@code preferred_username}).
 * @param roles     the principal's role codes (mirrors the {@code roles} claim).
 */
public record HumanLoginResponse(
        String token,
        long expiresAt,
        String tokenType,
        String username,
        List<String> roles) {

    /** Factory for a freshly minted Bearer login token. */
    public static HumanLoginResponse bearer(String token, long expiresAt,
                                            String username, List<String> roles) {
        return new HumanLoginResponse(token, expiresAt, "Bearer", username, roles);
    }

    /** Redact the token from any accidental log line. */
    @Override
    public String toString() {
        return "HumanLoginResponse[token=REDACTED, expiresAt=" + expiresAt
                + ", tokenType=" + tokenType + ", username=" + username
                + ", roles=" + roles + "]";
    }
}
