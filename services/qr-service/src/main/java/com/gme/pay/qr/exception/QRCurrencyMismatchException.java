package com.gme.pay.qr.exception;

/** Thrown when EMVCo tag 53 contains an unexpected currency code (e.g. not '410' for ZeroPay). */
public class QRCurrencyMismatchException extends QRParseException {

    public QRCurrencyMismatchException(String message) {
        super(QRErrorCode.QR_CURRENCY_MISMATCH, message);
    }
}
