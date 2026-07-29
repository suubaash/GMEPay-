package com.gme.pay.scheme.sendmn.dto;

/**
 * Request for POST /internal/scheme/sendmn/submit-mpm (maps to SendMN Confirm — the
 * money-moving call).
 *
 * @param txTokenNo      the TX_TOKEN_NO returned by verify-qr (idempotency key)
 * @param localAmountMnt MNT amount as a decimal string (Decimal(18,2)); mandatory —
 *                       static QRs may carry no amount, in which case the wallet user
 *                       keyed it in
 * @param merchantId     optional override; defaults to the merchant captured at verify-qr
 * @param reference      the hub's stable partner reference; backfill only — normally
 *                       already persisted at verify-qr (before Confirm, per ADR-016),
 *                       this fills it in for callers that omitted it there
 */
public record SubmitMpmRequest(
        String txTokenNo,
        String localAmountMnt,
        String merchantId,
        String reference
) {}
