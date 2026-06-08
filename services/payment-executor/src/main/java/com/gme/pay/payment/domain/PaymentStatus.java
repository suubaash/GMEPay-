package com.gme.pay.payment.domain;

/** Lifecycle states of a payment managed by Payment Executor. */
public enum PaymentStatus {
    PENDING,
    APPROVED,
    FAILED,
    UNCERTAIN,
    CANCELLED,
    REVERSED,
    REFUNDED
}
