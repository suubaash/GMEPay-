package com.gme.pay.qr.exception;

/**
 * Canonical error codes for QR parse and merchant-resolution failures (SCH-06 §3.4 and §3.5).
 */
public enum QRErrorCode {
    // --- parse errors ---
    QR_INVALID_CHECKSUM,
    QR_UNKNOWN_SCHEME,
    QR_MALFORMED,
    QR_CURRENCY_MISMATCH,

    // --- resolution errors ---
    MERCHANT_NOT_FOUND,
    MERCHANT_INACTIVE,
    QR_NOT_FOUND,
    QR_DEACTIVATED,
    QR_MERCHANT_MISMATCH,

    // --- CPM errors ---
    NO_SCHEME_FOR_LOCATION,
    PAYMENT_MODE_NOT_SUPPORTED,
    SCHEME_UNAVAILABLE,
    INSUFFICIENT_PREFUNDING,
    MISSING_IDEMPOTENCY_KEY,
    IDEMPOTENCY_KEY_REUSE,
    DUPLICATE_PARTNER_TXN_REF,
    TIMESTAMP_OUT_OF_RANGE,
    INVALID_SIGNATURE,
    DIRECTION_NOT_ENABLED
}
