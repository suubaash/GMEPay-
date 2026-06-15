package com.gme.pay.merchant.web;

/**
 * REST response DTO for {@code GET /v1/merchants/{qr}}.
 *
 * <p>This is a module-local DTO — it is NOT shared via lib-api-contracts because the
 * service owns this contract and consumers call it over HTTP.
 *
 * <p><strong>Consumer contract (UC-07-03 / B3 fix):</strong> field names MUST match
 * {@code payment-executor}'s {@code RestQrClient.MerchantResponse} exactly so Jackson
 * binds them without any custom deserializer:
 * <ul>
 *   <li>{@code merchantId}       — business merchant identifier</li>
 *   <li>{@code merchantName}     — human-readable merchant name (RestQrClient reads this field)</li>
 *   <li>{@code payoutCurrency}   — ISO 4217 currency (e.g. {@code KRW})</li>
 *   <li>{@code schemeId}         — payment scheme (e.g. {@code ZEROPAY})</li>
 *   <li>{@code active}           — primary boolean flag; true = may accept payments</li>
 *   <li>{@code status}           — lifecycle string ({@code ACTIVE} / {@code INACTIVE} /
 *                                  {@code SUSPENDED} / {@code DEACTIVATED}); fallback for
 *                                  RestQrClient when {@code active} is false</li>
 * </ul>
 *
 * <p>Additional informational fields ({@code qrCodeId}, {@code merchantType},
 * {@code feeType}, {@code city}, {@code mcc}) are included for completeness but are
 * not read by the current payment-executor client (Jackson ignores unknown fields there
 * via {@code @JsonIgnoreProperties(ignoreUnknown = true)}).
 */
public record MerchantResponse(
        String merchantId,
        String qrCodeId,
        /** Serialised as {@code merchantName} — matches RestQrClient field name exactly. */
        String merchantName,
        String merchantType,
        String feeType,
        String status,
        boolean active,
        /** ISO 4217 payout currency. Required by RestQrClient. */
        String payoutCurrency,
        /** Payment scheme identifier. Required by RestQrClient. */
        String schemeId,
        String city,
        String mcc) {
}
