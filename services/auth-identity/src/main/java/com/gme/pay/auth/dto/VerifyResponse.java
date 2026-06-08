package com.gme.pay.auth.dto;

/**
 * Response body for POST /internal/auth/verify.
 */
public record VerifyResponse(
        /** true = signature valid, timestamp in window, nonce not replayed */
        boolean valid,

        /** Resolved partner ID when valid=true; null otherwise. */
        Long partnerId,

        /** Human-readable error code when valid=false (e.g. "INVALID_SIGNATURE",
         *  "TIMESTAMP_DRIFT", "REPLAY_DETECTED", "INVALID_API_KEY"). */
        String errorCode,

        /** Correlation ID echoed from the request for tracing. */
        String requestId
) {

    /** Convenience factory for a successful verification. */
    public static VerifyResponse ok(Long partnerId, String requestId) {
        return new VerifyResponse(true, partnerId, null, requestId);
    }

    /** Convenience factory for a failed verification. */
    public static VerifyResponse fail(String errorCode, String requestId) {
        return new VerifyResponse(false, null, errorCode, requestId);
    }
}
