package com.gme.pay.errors;

/**
 * Canonical GMEPay+ API error codes (see API-05 error model). Each carries an HTTP status
 * and whether the client may safely retry.
 */
public enum ErrorCode {

    VALIDATION_ERROR(400, false),
    /**
     * Slice 8 post-activation immutability: once a partner has gone LIVE for the
     * first time ({@code partners.go_live_at} stamped), the identity-critical
     * columns (partner_code, country_of_incorporation, partner_type,
     * collection_ccy, settle_a_ccy) are locked. Any PATCH/PUT attempting to
     * mutate them is rejected with this code.
     */
    IMMUTABLE_AFTER_ACTIVATION(400, false),
    RATE_QUOTE_EXPIRED(409, false),
    MIN_MARGIN_VIOLATION(422, false),
    POOL_IDENTITY_VIOLATION(500, false),
    INSUFFICIENT_PREFUNDING(402, false),
    PARTNER_B_QUOTE_DEVIATION(422, false),
    PARTNER_B_QUOTE_UNAVAILABLE(503, true),
    MERCHANT_NOT_FOUND(404, false),
    NO_SCHEME_FOR_LOCATION(404, false),
    SCHEME_UNAVAILABLE(503, true),
    IDEMPOTENCY_CONFLICT(409, false),
    INTERNAL_ERROR(500, true);

    private final int httpStatus;
    private final boolean retryable;

    ErrorCode(int httpStatus, boolean retryable) {
        this.httpStatus = httpStatus;
        this.retryable = retryable;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public boolean retryable() {
        return retryable;
    }
}
