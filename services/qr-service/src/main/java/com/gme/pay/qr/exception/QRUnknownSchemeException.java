package com.gme.pay.qr.exception;

/** Thrown when no registered MAI slot or SchemeQRParser matches the payload. */
public class QRUnknownSchemeException extends QRParseException {

    public QRUnknownSchemeException(String message) {
        super(QRErrorCode.QR_UNKNOWN_SCHEME, message);
    }
}
