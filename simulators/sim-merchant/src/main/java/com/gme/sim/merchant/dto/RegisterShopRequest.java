package com.gme.sim.merchant.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for POST /v1/merchant/shops.
 * Maps to sim-scheme POST /v1/scheme/merchants fields.
 */
public record RegisterShopRequest(
        @NotBlank String name,
        @NotBlank String city,
        @NotBlank String mcc,
        // optional ZeroPay / KFTC fields
        String businessRegNo,
        String subMerchantId,
        String kftcInstitutionCode,
        String merchantType   // "SMALL_BIZ" | "GENERAL" | null
) {}
