package com.gme.pay.qr.exception;

/** Thrown when the merchant_id from the parsed QR is not present in the local DB. */
public class MerchantNotFoundException extends QRParseException {

    public MerchantNotFoundException(String merchantId) {
        super(QRErrorCode.MERCHANT_NOT_FOUND, "Merchant not found: " + merchantId);
    }
}
