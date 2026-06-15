package com.gme.sim.merchant.model;

/**
 * In-memory record for a registered shop.
 *
 * merchantId  – assigned by sim-scheme on registration.
 * name        – display name of the shop.
 * city        – city where the shop is located.
 * mcc         – ISO 18245 Merchant Category Code.
 * merchantType – "SMALL_BIZ" | "GENERAL" | null.
 */
public record ShopRecord(
        String merchantId,
        String name,
        String city,
        String mcc,
        String businessRegNo,
        String subMerchantId,
        String kftcInstitutionCode,
        String merchantType,
        String feeRate
) {}
