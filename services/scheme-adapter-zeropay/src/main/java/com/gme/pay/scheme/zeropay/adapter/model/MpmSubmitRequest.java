package com.gme.pay.scheme.zeropay.adapter.model;

import java.math.BigDecimal;

/**
 * Request to submit an MPM (Merchant-Presented Mode) payment to ZeroPay.
 *
 * <p>{@code qrPayload} is the raw EMVCo QR string from which the scheme mode
 * (static/dynamic) and embedded amount are derived. It is required for real ZeroPay
 * calls against sim-scheme.
 */
public record MpmSubmitRequest(
        String merchantId,
        BigDecimal amountKrw,
        String currency,
        String partnerTxnRef,
        String idempotencyKey,
        String qrPayload
) {}
