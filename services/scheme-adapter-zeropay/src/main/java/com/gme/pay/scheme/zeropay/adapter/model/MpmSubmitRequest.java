package com.gme.pay.scheme.zeropay.adapter.model;

import java.math.BigDecimal;

/** Request to submit an MPM (Merchant-Presented Mode) payment to ZeroPay. */
public record MpmSubmitRequest(
        String merchantId,
        BigDecimal amountKrw,
        String currency,
        String partnerTxnRef,
        String idempotencyKey
) {}
