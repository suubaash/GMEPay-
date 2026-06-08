package com.gme.pay.scheme.zeropay.status;

/** Thrown when a ZeroPay result code is not recognised in the canonical mapping. */
public class UnknownZeroPayResultCodeException extends RuntimeException {

    private final String rawCode;

    public UnknownZeroPayResultCodeException(String rawCode) {
        super("Unknown ZeroPay result code: " + rawCode);
        this.rawCode = rawCode;
    }

    public String rawCode() {
        return rawCode;
    }
}
