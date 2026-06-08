package com.gme.pay.qr.exception;

/** Thrown when CRC-16/CCITT checksum in tag 63 does not match computed value. */
public class QRInvalidChecksumException extends QRParseException {

    public QRInvalidChecksumException(String message) {
        super(QRErrorCode.QR_INVALID_CHECKSUM, message);
    }
}
