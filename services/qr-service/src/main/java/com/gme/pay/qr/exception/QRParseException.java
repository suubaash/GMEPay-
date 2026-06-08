package com.gme.pay.qr.exception;

/**
 * Base unchecked exception for all QR parse and merchant-resolution failures.
 * Sub-classes carry a specific {@link QRErrorCode}.
 */
public class QRParseException extends RuntimeException {

    private final QRErrorCode errorCode;

    public QRParseException(QRErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public QRParseException(QRErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public QRErrorCode getErrorCode() {
        return errorCode;
    }
}
