package com.gme.pay.auth.dto;

/**
 * Request body for POST /internal/auth/verify.
 *
 * All fields correspond to headers that the api-gateway extracts from the inbound
 * partner request and forwards to auth-identity for server-side HMAC verification.
 */
public record VerifyRequest(
        /** Partner API key (from X-API-Key header). */
        String apiKey,

        /** HTTP method of the original request (e.g. "POST"). */
        String httpMethod,

        /** Full path including query string (e.g. "/v1/payments?foo=bar"). */
        String pathWithQuery,

        /** ISO-8601 UTC or Unix-epoch timestamp from X-Timestamp header. */
        String timestamp,

        /** Unique nonce from X-Nonce header. */
        String nonce,

        /** Hex-encoded HMAC-SHA256 signature from X-Signature header. */
        String signature,

        /** Hex-encoded SHA-256 digest of the raw request body (empty string = empty body). */
        String bodyHash
) {}
