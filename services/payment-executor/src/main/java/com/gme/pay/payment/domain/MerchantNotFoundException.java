package com.gme.pay.payment.domain;

/**
 * Raised when a merchant cannot be resolved for a scanned QR — the merchant-qr-data lookup returned a
 * miss (404) or was unreachable — and the service is in the default STRICT mode. Strict mode hard-fails
 * rather than synthesizing an UNKNOWN merchant (the lenient bypass is now dev-only). Maps to the
 * canonical {@link com.gme.pay.errors.ErrorCode#MERCHANT_NOT_FOUND} (404) via {@code
 * PaymentExceptionHandler}.
 */
public class MerchantNotFoundException extends PaymentException {

    public MerchantNotFoundException(String message) {
        super(message);
    }

    public MerchantNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
