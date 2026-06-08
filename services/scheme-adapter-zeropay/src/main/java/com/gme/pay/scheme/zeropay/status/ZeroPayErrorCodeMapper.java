package com.gme.pay.scheme.zeropay.status;

import com.gme.pay.errors.ErrorCode;

import java.util.Map;

/**
 * Maps ZeroPay scheme result codes to GMEPay+ canonical {@link ErrorCode} values.
 *
 * <p>Each ZeroPay code is mapped to the canonical error code that best represents the
 * business condition, so callers never depend on scheme-specific code strings.</p>
 */
public final class ZeroPayErrorCodeMapper {

    private static final Map<ZeroPayResultCode, ErrorCode> MAPPING = Map.ofEntries(
            Map.entry(ZeroPayResultCode.MERCHANT_NOT_FOUND,          ErrorCode.MERCHANT_NOT_FOUND),
            Map.entry(ZeroPayResultCode.MERCHANT_INACTIVE,           ErrorCode.MERCHANT_NOT_FOUND),
            Map.entry(ZeroPayResultCode.QR_NOT_FOUND,                ErrorCode.MERCHANT_NOT_FOUND),
            Map.entry(ZeroPayResultCode.QR_DEACTIVATED,              ErrorCode.MERCHANT_NOT_FOUND),
            Map.entry(ZeroPayResultCode.AMOUNT_LIMIT_EXCEEDED,       ErrorCode.VALIDATION_ERROR),
            Map.entry(ZeroPayResultCode.DAILY_LIMIT_EXCEEDED,        ErrorCode.VALIDATION_ERROR),
            Map.entry(ZeroPayResultCode.DUPLICATE_REQUEST,           ErrorCode.IDEMPOTENCY_CONFLICT),
            Map.entry(ZeroPayResultCode.INVALID_TRANSACTION_STATE,   ErrorCode.VALIDATION_ERROR),
            Map.entry(ZeroPayResultCode.PAYMENT_ALREADY_CANCELLED,   ErrorCode.VALIDATION_ERROR),
            Map.entry(ZeroPayResultCode.REGISTRATION_AMOUNT_MISMATCH,ErrorCode.VALIDATION_ERROR),
            Map.entry(ZeroPayResultCode.INVALID_MERCHANT_TYPE,       ErrorCode.VALIDATION_ERROR),
            Map.entry(ZeroPayResultCode.INVALID_CURRENCY,            ErrorCode.VALIDATION_ERROR),
            Map.entry(ZeroPayResultCode.BATCH_VALIDATION_FAILED,     ErrorCode.VALIDATION_ERROR),
            Map.entry(ZeroPayResultCode.UNKNOWN_FILE_TYPE,           ErrorCode.VALIDATION_ERROR),
            Map.entry(ZeroPayResultCode.SYSTEM_UNAVAILABLE,          ErrorCode.SCHEME_UNAVAILABLE),
            Map.entry(ZeroPayResultCode.TIMEOUT,                     ErrorCode.SCHEME_UNAVAILABLE),
            Map.entry(ZeroPayResultCode.INTERNAL_ERROR,              ErrorCode.INTERNAL_ERROR)
    );

    private ZeroPayErrorCodeMapper() {}

    /**
     * Maps a {@link ZeroPayResultCode} to the canonical {@link ErrorCode}.
     *
     * @param zeroPayCode the ZeroPay-specific result code
     * @return the canonical error code; falls back to {@link ErrorCode#INTERNAL_ERROR}
     *         for unmapped success codes or future codes added to ZeroPay
     */
    public static ErrorCode toErrorCode(ZeroPayResultCode zeroPayCode) {
        if (zeroPayCode.isSuccess()) {
            // success codes should not be mapped to errors; return INTERNAL_ERROR as a safety net
            return ErrorCode.INTERNAL_ERROR;
        }
        return MAPPING.getOrDefault(zeroPayCode, ErrorCode.INTERNAL_ERROR);
    }

    /**
     * Convenience overload that first resolves the raw string code and then maps it.
     *
     * @param rawCode the raw result code string from ZeroPay (e.g. "9002")
     * @return the canonical {@link ErrorCode}
     * @throws UnknownZeroPayResultCodeException if the raw code is not in the enum
     */
    public static ErrorCode toErrorCode(String rawCode) {
        return toErrorCode(ZeroPayResultCode.of(rawCode));
    }
}
