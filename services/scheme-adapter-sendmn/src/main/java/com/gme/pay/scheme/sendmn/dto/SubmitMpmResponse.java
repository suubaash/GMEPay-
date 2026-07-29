package com.gme.pay.scheme.sendmn.dto;

/**
 * Response for POST /internal/scheme/sendmn/submit-mpm.
 *
 * @param txTokenNo           echoed idempotency key
 * @param status              canonical state: APPROVED / PENDING / UNKNOWN (never auto-failed)
 * @param paymentNo           SendMN API tracking number (nullable until known)
 * @param paymentReceiptNo    SendMN control number (nullable until known)
 * @param fxUsdBuyRate        registered MNT/USD buy rate the settlement was computed at
 * @param settlementAmountUsd USD settlement amount (scale 4) sent on Confirm
 */
public record SubmitMpmResponse(
        String txTokenNo,
        String status,
        String paymentNo,
        String paymentReceiptNo,
        String fxUsdBuyRate,
        String settlementAmountUsd
) {}
