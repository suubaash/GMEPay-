package com.gme.pay.errors;

/** Standard error envelope returned by the Partner API. */
public record ApiError(String code, String message, boolean retryable, String requestId) {

    public static ApiError of(ErrorCode code, String message, String requestId) {
        return new ApiError(code.name(), message, code.retryable(), requestId);
    }
}
