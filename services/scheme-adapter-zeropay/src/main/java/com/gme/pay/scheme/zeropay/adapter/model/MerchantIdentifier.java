package com.gme.pay.scheme.zeropay.adapter.model;

/**
 * Identifies a ZeroPay merchant extracted from an EMVCo QR payload (SCH-06 §3.4).
 */
public record MerchantIdentifier(
        String merchantId,
        String qrCodeId,
        String merchantName,
        String merchantTypeCode
) {}
