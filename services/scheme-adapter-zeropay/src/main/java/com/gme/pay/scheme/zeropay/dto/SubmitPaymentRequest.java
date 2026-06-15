package com.gme.pay.scheme.zeropay.dto;

import java.math.BigDecimal;

/**
 * Request DTO for {@code POST /internal/scheme/zeropay/submit}.
 *
 * <p>{@code qrPayload} is the raw EMVCo QR string; required for MPM modes so the adapter
 * can determine static vs dynamic and extract the embedded amount.
 */
public record SubmitPaymentRequest(
        String merchantId,
        BigDecimal amountKrw,
        String currency,
        String partnerTxnRef,
        String idempotencyKey,
        String paymentMode,
        String qrPayload
) {}
