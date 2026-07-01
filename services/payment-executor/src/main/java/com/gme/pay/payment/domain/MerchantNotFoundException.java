package com.gme.pay.payment.domain;

/**
 * Thrown when merchant-qr-data answers {@code 404 MERCHANT_NOT_FOUND} for a scanned QR —
 * i.e. the merchant is definitively unknown (as opposed to merchant-qr-data being
 * unreachable). This is a normal business decline, NOT a server error: callers map it to a
 * declined wallet result with reason {@code MERCHANT_NOT_FOUND}, never to an HTTP 500.
 *
 * <p>Extends {@link PaymentException} so existing {@code catch (PaymentException)} sites and
 * tests keep working.
 */
public class MerchantNotFoundException extends PaymentException {

    public MerchantNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
