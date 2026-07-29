package com.gme.sim.sendmn.model;

import java.math.BigDecimal;

/**
 * A seeded Mongolian merchant with its static MPM QR.
 *
 * @param merchantId  GUID — what VerifyQr returns as MERCHANT_ID (doc example)
 * @param numericId   SendMN-internal numeric id — what Confirm's response echoes
 *                    (digest gotcha: Confirm response MERCHANT_ID is numeric, informational)
 * @param name        business name
 * @param address     nullable
 * @param terminalId  nullable
 * @param fixedAmount MNT amount carried in the QR, or null (static QR without amount —
 *                    the user keys the amount in)
 * @param qrCode      EMVCo MPM static payload (QR_TYPE 11, GUID A000000843000101 = QPay)
 */
public record Merchant(
        String merchantId,
        String numericId,
        String name,
        String address,
        String terminalId,
        BigDecimal fixedAmount,
        String qrCode
) {}
