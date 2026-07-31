package com.gme.pay.scheme.sendmn.dto;

/**
 * Canonical VerifyQr result returned to payment-executor.
 *
 * @param txTokenNo      adapter-generated TX_TOKEN_NO — the hub must echo it on submit-mpm/status
 * @param merchantId     SendMN merchant identifier (GUID)
 * @param merchantName   merchant business name
 * @param merchantAddress merchant address (nullable)
 * @param terminalId     POS/terminal id (nullable)
 * @param qrType         "11" = MPM static QR
 * @param localAmountMnt MNT amount embedded in the QR as a decimal string; null when the
 *                       static QR carries no amount (user must key it in)
 * @param currency       local currency, "MNT"
 */
public record VerifyQrResponse(
        String txTokenNo,
        String merchantId,
        String merchantName,
        String merchantAddress,
        String terminalId,
        String qrType,
        String localAmountMnt,
        String currency
) {}
