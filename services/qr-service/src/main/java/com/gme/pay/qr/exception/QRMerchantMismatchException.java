package com.gme.pay.qr.exception;

/** Thrown when the qr_code row references a different merchant than the one resolved from merchantId. */
public class QRMerchantMismatchException extends QRParseException {

    public QRMerchantMismatchException(String qrCodeId) {
        super(QRErrorCode.QR_MERCHANT_MISMATCH,
              "QR code " + qrCodeId + " belongs to a different merchant than the payload claims");
    }
}
