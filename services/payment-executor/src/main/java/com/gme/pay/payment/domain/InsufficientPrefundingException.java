package com.gme.pay.payment.domain;

import java.math.BigDecimal;

/** Thrown when OVERSEAS partner's prefunding balance is too low for the requested deduction. */
public class InsufficientPrefundingException extends PaymentException {

    private final BigDecimal available;
    private final BigDecimal required;

    public InsufficientPrefundingException(BigDecimal available, BigDecimal required) {
        super(String.format(
                "Insufficient prefunding: required %s USD but only %s USD available",
                required.toPlainString(), available.toPlainString()));
        this.available = available;
        this.required = required;
    }

    public BigDecimal available() { return available; }
    public BigDecimal required() { return required; }
}
