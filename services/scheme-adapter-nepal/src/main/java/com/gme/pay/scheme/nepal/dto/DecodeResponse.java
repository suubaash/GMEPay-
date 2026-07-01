package com.gme.pay.scheme.nepal.dto;

/**
 * Canonical decode result returned to payment-executor.
 *
 * @param network      "fonepay" / "nepalpay" / "khalti" / etc.
 * @param merchantId   network-specific merchant identifier (null for wallet networks)
 * @param merchantName merchant / receiver display name
 * @param merchantCity merchant city (null when not encoded)
 * @param amountPaisa  embedded amount in paisa when the QR is dynamic; null when static
 * @param currency     ISO 4217 code, defaults "NPR"
 */
public record DecodeResponse(
        String network,
        String merchantId,
        String merchantName,
        String merchantCity,
        Long amountPaisa,
        String currency
) {}
