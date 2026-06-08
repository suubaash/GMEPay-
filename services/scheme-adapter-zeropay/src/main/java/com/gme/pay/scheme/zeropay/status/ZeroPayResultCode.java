package com.gme.pay.scheme.zeropay.status;

/**
 * ZeroPay scheme result codes as defined in SCH-06 §6.1 (real-time) and §5.3 (batch).
 *
 * <p>Code {@code 00} and {@code 0000} are success; all others are failures.
 * Codes with prefix {@code 9} are business errors (non-retryable);
 * codes with prefix {@code 5} are system/transient errors (retryable).</p>
 */
public enum ZeroPayResultCode {

    // ---- Success ----
    SUCCESS("00"),
    BATCH_SUCCESS("0000"),

    // ---- Business errors (non-retryable) ----
    MERCHANT_NOT_FOUND("1001"),
    MERCHANT_INACTIVE("1002"),
    QR_NOT_FOUND("1003"),
    QR_DEACTIVATED("1004"),
    AMOUNT_LIMIT_EXCEEDED("2001"),
    DAILY_LIMIT_EXCEEDED("2002"),
    DUPLICATE_REQUEST("3001"),
    INVALID_TRANSACTION_STATE("3002"),
    PAYMENT_ALREADY_CANCELLED("3003"),
    REGISTRATION_AMOUNT_MISMATCH("9002"),
    INVALID_MERCHANT_TYPE("9003"),
    INVALID_CURRENCY("9004"),
    BATCH_VALIDATION_FAILED("9005"),
    UNKNOWN_FILE_TYPE("9099"),

    // ---- Transient / system errors (retryable) ----
    SYSTEM_UNAVAILABLE("5000"),
    TIMEOUT("5001"),
    INTERNAL_ERROR("5999");

    private final String code;

    ZeroPayResultCode(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /**
     * Looks up a {@link ZeroPayResultCode} by its raw string value.
     *
     * @param rawCode the string code received from ZeroPay (e.g. "00", "9002")
     * @return the matching enum constant
     * @throws UnknownZeroPayResultCodeException if the code is not recognised
     */
    public static ZeroPayResultCode of(String rawCode) {
        for (ZeroPayResultCode rc : values()) {
            if (rc.code.equals(rawCode)) {
                return rc;
            }
        }
        throw new UnknownZeroPayResultCodeException(rawCode);
    }

    /** Returns true if this code represents a successful outcome. */
    public boolean isSuccess() {
        return this == SUCCESS || this == BATCH_SUCCESS;
    }

    /** Returns true if retrying the operation may succeed. */
    public boolean isRetryable() {
        return this == SYSTEM_UNAVAILABLE || this == TIMEOUT || this == INTERNAL_ERROR;
    }
}
