package com.gme.pay.qr.exception;

/** Thrown when the resolved merchant is suspended or deactivated. */
public class MerchantInactiveException extends QRParseException {

    public MerchantInactiveException(String merchantId) {
        super(QRErrorCode.MERCHANT_INACTIVE, "Merchant is not active: " + merchantId);
    }
}
