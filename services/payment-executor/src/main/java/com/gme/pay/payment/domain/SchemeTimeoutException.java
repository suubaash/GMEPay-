package com.gme.pay.payment.domain;

/** Thrown when the downstream scheme does not respond within the configured SLA. */
public class SchemeTimeoutException extends PaymentException {

    public SchemeTimeoutException(String schemeId) {
        super("Scheme " + schemeId + " did not respond within the configured timeout");
    }
}
