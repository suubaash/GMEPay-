package com.gme.pay.errors;

import com.gme.pay.correlation.CorrelationHeaders;
import java.util.UUID;
import org.slf4j.MDC;

/** Standard error envelope returned by the Partner API. */
public record ApiError(String code, String message, boolean retryable, String requestId) {

    public static ApiError of(ErrorCode code, String message, String requestId) {
        return new ApiError(code.name(), message, code.retryable(), resolveRequestId(requestId));
    }

    /**
     * Convenience factory that resolves the request id from the correlation id (MDC) and falls back
     * to a generated UUID. Use when the caller has no explicit request id of its own.
     */
    public static ApiError of(ErrorCode code, String message) {
        return of(code, message, null);
    }

    /**
     * Resolve the id to stamp on the error so a partner's error and the server logs share ONE id: an
     * explicit non-blank caller-supplied id wins (back-compat); otherwise the per-request correlation
     * id from the SLF4J MDC ({@link CorrelationHeaders#MDC_KEY}) — making the error id == the trace id;
     * otherwise a generated UUID so the field is NEVER null.
     */
    private static String resolveRequestId(String requestId) {
        if (requestId != null && !requestId.isBlank()) {
            return requestId;
        }
        String correlationId = MDC.get(CorrelationHeaders.MDC_KEY);
        if (correlationId != null && !correlationId.isBlank()) {
            return correlationId;
        }
        return UUID.randomUUID().toString();
    }
}
