package com.gme.pay.scheme.zeropay.dto;

import java.math.BigDecimal;

/**
 * Request DTO for {@code POST /internal/scheme/zeropay/submit}.
 */
public record SubmitPaymentRequest(
        String merchantId,
        BigDecimal amountKrw,
        String currency,
        String partnerTxnRef,
        String idempotencyKey,
        String paymentMode
) {}
