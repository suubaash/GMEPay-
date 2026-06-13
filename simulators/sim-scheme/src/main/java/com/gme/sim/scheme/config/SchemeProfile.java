package com.gme.sim.scheme.config;

/**
 * Supported QR scheme profiles.
 * KHQR  – National Bank of Cambodia scheme; payout in KHR.
 * ZEROPAY – South Korea ZeroPay; payout in KRW.
 */
public enum SchemeProfile {

    KHQR("KHQR", "KHR", "116", "KH", 29),
    ZEROPAY("ZEROPAY", "KRW", "410", "KR", 29);

    /** Human-readable scheme identifier returned in payloads. */
    public final String schemeId;
    /** ISO 4217 alphabetic currency code for merchant payout. */
    public final String payoutCurrency;
    /** ISO 4217 numeric currency code (goes into EMVCo tag 53). */
    public final String numericCurrencyCode;
    /** ISO 3166-1 alpha-2 country code (EMVCo tag 58). */
    public final String countryCode;
    /** EMVCo tag number for merchant account information sub-TLV. */
    public final int merchantAccountTag;

    SchemeProfile(String schemeId, String payoutCurrency,
                  String numericCurrencyCode, String countryCode,
                  int merchantAccountTag) {
        this.schemeId = schemeId;
        this.payoutCurrency = payoutCurrency;
        this.numericCurrencyCode = numericCurrencyCode;
        this.countryCode = countryCode;
        this.merchantAccountTag = merchantAccountTag;
    }
}
