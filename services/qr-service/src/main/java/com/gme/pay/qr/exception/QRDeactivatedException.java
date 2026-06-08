package com.gme.pay.qr.exception;

/** Thrown when the QR code record is deactivated in the local DB. */
public class QRDeactivatedException extends QRParseException {

    public QRDeactivatedException(String qrCodeId) {
        super(QRErrorCode.QR_DEACTIVATED, "QR code is deactivated: " + qrCodeId);
    }
}
