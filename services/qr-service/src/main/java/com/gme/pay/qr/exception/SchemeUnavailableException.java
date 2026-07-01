package com.gme.pay.qr.exception;

/**
 * Thrown when a real QR scheme is wired but unreachable during CPM prepare (WBS 5.3-T06/T15).
 *
 * <p>Maps to HTTP 422 {@code SCHEME_UNAVAILABLE} (retryable). The local-issuance fallback never
 * throws this; it is reserved for the genuine scheme-adapter implementation.
 */
public class SchemeUnavailableException extends QRParseException {

    public SchemeUnavailableException(String message) {
        super(QRErrorCode.SCHEME_UNAVAILABLE, message);
    }

    public SchemeUnavailableException(String message, Throwable cause) {
        super(QRErrorCode.SCHEME_UNAVAILABLE, message, cause);
    }
}
