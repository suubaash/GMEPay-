package com.gme.pay.merchant.sync;

/**
 * Parsed row from a ZeroPay merchant-type file (ZP0041, ZP0045, ZP0051).
 *
 * <p>Immutable value object. {@code recordType} is {@code null} for full-list
 * files (ZP0051) that do not carry a change-type marker.
 *
 * @param recordType   "MN" (new), "MC" (change), "MD" (delete), or {@code null} for ZP0051
 * @param merchantId   ZeroPay merchant identifier (CHAR 10)
 * @param name         Human-readable merchant name
 * @param merchantType e.g. RETAIL, FOOD_BEVERAGE
 * @param feeType      e.g. DOMESTIC, CROSSBORDER
 * @param status       ACTIVE | INACTIVE | SUSPENDED | DEACTIVATED
 * @param payoutCurrency ISO 4217 (e.g. KRW)
 * @param schemeId     e.g. ZEROPAY
 * @param city         City / locality
 * @param mcc          ISO 18245 MCC (4-digit string)
 */
public record ParsedMerchantRow(
        String recordType,
        String merchantId,
        String name,
        String merchantType,
        String feeType,
        String status,
        String payoutCurrency,
        String schemeId,
        String city,
        String mcc) {

    /** Returns {@code true} when the record type signals a logical deletion. */
    public boolean isDelete() {
        return "MD".equalsIgnoreCase(recordType);
    }

    /** Returns {@code true} when the merchant status is ACTIVE. */
    public boolean isActive() {
        return "ACTIVE".equalsIgnoreCase(status);
    }
}
