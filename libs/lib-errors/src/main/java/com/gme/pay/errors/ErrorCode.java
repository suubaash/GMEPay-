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
    /**
     * The transaction breaches a configured per-partner limit (per-transaction min/max, or — once
     * cumulative tracking lands — a daily/monthly/annual cap). Raised during AUTHORIZE, before any
     * side effect, so an over-limit remittance is declined cleanly. Non-retryable (the amount must
     * change). Covers the statutory 소액해외송금업 per-transaction ceiling.
     */
    TRANSACTION_LIMIT_EXCEEDED(422, false),
    /**
     * The transaction would push the partner's CUMULATIVE volume over a configured rolling cap
     * (daily / monthly / annual USD — V020). Raised during AUTHORIZE under the per-partner row lock
     * (race-free), before the irreversible scheme submit. Non-retryable until the period rolls over.
     */
    CUMULATIVE_LIMIT_EXCEEDED(422, false),
    PARTNER_B_QUOTE_DEVIATION(422, false),
    PARTNER_B_QUOTE_UNAVAILABLE(503, true),
    MERCHANT_NOT_FOUND(404, false),
    NO_SCHEME_FOR_LOCATION(404, false),
    SCHEME_UNAVAILABLE(503, true),
    IDEMPOTENCY_CONFLICT(409, false),
    /**
     * The caller is not authenticated for the requested operation. Currently raised by the
     * service-to-service internal-auth gate ({@code gmepay.internal-auth}) when a request reaches an
     * internal-only endpoint (e.g. auth-identity's {@code /v1/rbac/**}, {@code /v1/approvals/**})
     * without the shared {@code X-Gme-Internal} token only trusted in-cluster callers (the gateway
     * resolver, the ops BFF) possess. Non-retryable: the caller is not a trusted internal service.
     */
    UNAUTHORIZED(401, false),
    /**
     * The caller is authenticated but not permitted to access the requested resource — e.g. a
     * partner asking for another partner's payment/transaction (cross-tenant access). Distinct from
     * {@link #UNAUTHORIZED} (no/invalid credentials). Raised by payment-executor's GET payment/balance
     * surfaces once partner-scoping lands. Non-retryable.
     */
    FORBIDDEN(403, false),
    /**
     * The referenced payment cannot be found for the caller — either it does not exist or it is not
     * visible to this partner (payment-executor GET {@code /v1/payments/{id}}). Non-retryable.
     */
    PAYMENT_NOT_FOUND(404, false),
    /**
     * The merchant resolved for the QR/CPM token is currently SUSPENDED (temporary hold). The payment
     * is declined before any side effect. Non-retryable until the merchant is reinstated. Raised by
     * merchant-qr-data strict-mode validation.
     */
    MERCHANT_SUSPENDED(422, false),
    /**
     * The merchant resolved for the QR/CPM token is DEACTIVATED (terminal off-boarding). The payment
     * is declined before any side effect. Non-retryable. Raised by merchant-qr-data strict-mode
     * validation.
     */
    MERCHANT_DEACTIVATED(422, false),
    /**
     * Scheme rows exist for the location + direction but none is wired for the requested presentment
     * mode (CPM/MPM). Distinct from {@link #NO_SCHEME_FOR_LOCATION} so the wallet can prompt the
     * customer to switch modes rather than fail hard. Raised by smart-router scheme-for-location
     * resolution (mirrors its local {@code ResolutionError.PAYMENT_MODE_NOT_SUPPORTED}).
     */
    PAYMENT_MODE_NOT_SUPPORTED(409, false),
    /**
     * Scheme rows exist for the location but none is enabled for the requested transaction direction
     * (INBOUND/OUTBOUND/DOMESTIC). Distinct from {@link #NO_SCHEME_FOR_LOCATION} so the corridor's
     * existence is still signalled. Raised by smart-router scheme-for-location resolution (mirrors its
     * local {@code ResolutionError.DIRECTION_NOT_ENABLED}).
     */
    DIRECTION_NOT_ENABLED(409, false),
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
