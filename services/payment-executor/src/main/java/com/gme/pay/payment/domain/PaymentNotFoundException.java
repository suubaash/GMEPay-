package com.gme.pay.payment.domain;

/**
 * Raised when a GET /v1/payments/{id} lookup finds no payment for the supplied id <em>owned by the
 * calling partner</em> (backlog 5.2-T16). Mapped to HTTP 404; a payment owned by a different partner
 * also raises this (404, not 403) so ownership is never leaked.
 */
public class PaymentNotFoundException extends PaymentException {

    public PaymentNotFoundException(String paymentId) {
        super("payment not found: " + paymentId);
    }
}
