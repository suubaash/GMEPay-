package com.gme.pay.scheme.zeropay.dto;

/**
 * Response DTO for {@code POST /internal/scheme/zeropay/submit}.
 */
public record SubmitPaymentResponse(
        String zeroPayTxnRef,
        String resultCode,
        String resultMessage,
        boolean success
) {}
