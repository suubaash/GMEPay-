package com.gme.pay.merchant.web;

/**
 * Request body for {@code POST /v1/merchants} — a write-through registration that mirrors a
 * merchant into the lookup store so a scanned QR resolves at payment time.
 *
 * <p>{@code qrCodeId} is the lookup key. In the ZeroPay sandbox this is the FULL EMVCo QR
 * payload string the wallet scans (payment-executor resolves {@code GET /v1/merchants/{qr}}
 * with exactly that string), so the mirror must key on the same value.
 *
 * <p>Only {@code qrCodeId} is required; the rest default sensibly for the sandbox so a minimal
 * terminal registration still produces an active, resolvable merchant.
 */
public record MerchantUpsertRequest(
        String qrCodeId,
        String merchantId,
        String merchantName,
        String merchantType,
        String feeType,
        /** Lifecycle string; when blank it is derived from {@code active}. */
        String status,
        /** Whether the merchant may accept payments. Defaults to true when omitted. */
        Boolean active,
        String payoutCurrency,
        String schemeId,
        String city,
        String mcc) {
}
