package com.gme.pay.qr.exception;

/** Thrown when a mandatory EMVCo tag is absent or the TLV structure is invalid. */
public class QRMalformedException extends QRParseException {

    public QRMalformedException(String message) {
        super(QRErrorCode.QR_MALFORMED, message);
    }
}
