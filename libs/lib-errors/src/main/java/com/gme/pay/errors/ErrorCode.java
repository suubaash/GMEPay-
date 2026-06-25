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
    /**
     * The settlement amount/currency the partner asserts in the payment request
     * ({@code collection_amount}/{@code collection_currency}) does not match the locked
     * rate quote. The payment is rejected before any side effect (no transaction record,
     * no prefunding deduction, no scheme submission). Non-retryable: the partner must
     * re-quote or resend the amount it actually agreed to.
     */
    QUOTE_AMOUNT_MISMATCH(422, false),
    MIN_MARGIN_VIOLATION(422, false),
    POOL_IDENTITY_VIOLATION(500, false),
    INSUFFICIENT_PREFUNDING(402, false),
    /**
     * The scheme reports GME's prepaid balance with it (+ any per-scheme credit) is insufficient to
     * fund the merchant payout. Raised during AUTHORIZE (before the customer is charged) so a short
     * GME→scheme float declines cleanly. Non-retryable until GME tops up its scheme float.
     */
    SCHEME_BALANCE_INSUFFICIENT(402, false),
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
