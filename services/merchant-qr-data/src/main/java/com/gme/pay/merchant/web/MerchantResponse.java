package com.gme.pay.merchant.web;

/**
 * REST response DTO for {@code GET /v1/merchants/{qr}}.
 *
 * <p>This is a module-local DTO — it is NOT shared via lib-api-contracts because the
 * service owns this contract and consumers call it over HTTP.
 */
public record MerchantResponse(
        String merchantId,
        String qrCodeId,
        String name,
        String merchantType,
        String feeType,
        String status,
        boolean active) {
}
