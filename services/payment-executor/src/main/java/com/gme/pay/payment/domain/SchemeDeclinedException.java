package com.gme.pay.payment.domain;

/** Thrown when the downstream scheme synchronously declines the payment. */
public class SchemeDeclinedException extends PaymentException {

    private final String schemeErrorCode;

    public SchemeDeclinedException(String schemeErrorCode, String schemeMessage) {
        super("Scheme declined: [" + schemeErrorCode + "] " + schemeMessage);
        this.schemeErrorCode = schemeErrorCode;
    }

    public String schemeErrorCode() { return schemeErrorCode; }
}
