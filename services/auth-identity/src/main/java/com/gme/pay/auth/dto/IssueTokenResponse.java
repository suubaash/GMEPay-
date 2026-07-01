package com.gme.pay.auth.dto;

/**
 * Response body for {@code POST /internal/auth/token/issue}.
 *
 * @param token       the compact serialized HS256 JWT (header.payload.signature).
 * @param expiresAt   epoch-second expiry of the token ({@code exp} claim).
 * @param tokenType   always {@code "Bearer"} — RFC 6750 token type for the
 *                    {@code Authorization} header on downstream internal calls.
 */
public record IssueTokenResponse(
        String token,
        long expiresAt,
        String tokenType) {

    /** Factory for a freshly minted Bearer token. */
    public static IssueTokenResponse bearer(String token, long expiresAt) {
        return new IssueTokenResponse(token, expiresAt, "Bearer");
    }

    /** Redact the token from any accidental log line. */
    @Override
    public String toString() {
        return "IssueTokenResponse[token=REDACTED, expiresAt=" + expiresAt
                + ", tokenType=" + tokenType + "]";
    }
}
