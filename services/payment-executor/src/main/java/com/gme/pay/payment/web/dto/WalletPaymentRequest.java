package com.gme.pay.payment.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * Request body for POST /v1/pay — the GMERemit wallet-facing payment entry point.
 *
 * <p>The wallet scans the merchant QR code, passes the raw payload here with the amount it wishes
 * to pay. For a domestic ZeroPay/GMEREMIT payment the amount is KRW→KRW (no FX) and the ₩500 fixed
 * service fee is calculated server-side. For a cross-border scheme (e.g. Nepal Fonepay) the wallet
 * sends the amount already expressed in the merchant currency together with a {@code currency}
 * field; the hub then executes the payment in that currency rather than assuming KRW.
 *
 * <p><b>{@code currency} is optional and defaults to {@code KRW}.</b> When absent (or explicitly
 * {@code KRW}) the request is byte-for-byte back-compatible: {@code amountKrw} is a KRW amount and
 * behaviour is identical to the pre-currency contract. When present and non-KRW, the value of
 * {@code amountKrw} is interpreted as the amount in {@code currency} (the field name is retained
 * for wire compatibility). This endpoint does NOT perform KRW→foreign FX — the wallet computes the
 * KRW debit on its side; the hub only executes the payment in the given currency.
 */
public record WalletPaymentRequest(
        /** Raw EMVCo QR string scanned from the merchant terminal. */
        @JsonProperty("qrPayload")      String qrPayload,
        /** Amount the payer intends to send, expressed in {@link #currency} (decimal string per money convention). */
        @JsonProperty("amountKrw")      String amountKrw,
        /** Originating partner identifier — must be "GMEREMIT" for this endpoint. */
        @JsonProperty("partner")        String partner,
        /** Wallet user reference (e.g. wallet account ID or user UUID). */
        @JsonProperty("userRef")        String userRef,
        /** Optional ISO-4217 pay currency; defaults to {@code KRW} when absent (full back-compat). */
        @JsonProperty("currency")       String currency
) {
    /** Default pay currency when the wallet omits {@code currency} (domestic ZeroPay/KRW path). */
    public static final String DEFAULT_CURRENCY = "KRW";

    public void validate() {
        assertRequired("qrPayload", qrPayload);
        assertRequired("amountKrw", amountKrw);
        assertRequired("partner", partner);
        assertRequired("userRef", userRef);
        if (amountKrw != null && !amountKrw.matches("^[0-9]+(\\.[0-9]+)?$")) {
            throw new IllegalArgumentException(
                    "amountKrw must be a valid positive decimal, got: " + amountKrw);
        }
        new BigDecimal(amountKrw); // parse check
        if (currency != null && !currency.isBlank() && !currency.matches("^[A-Za-z]{3}$")) {
            throw new IllegalArgumentException(
                    "currency must be a 3-letter ISO-4217 code, got: " + currency);
        }
    }

    /** Resolved pay currency — {@code currency} when supplied, else {@code KRW}. Always upper-case. */
    public String payCurrency() {
        return (currency == null || currency.isBlank())
                ? DEFAULT_CURRENCY
                : currency.toUpperCase(java.util.Locale.ROOT);
    }

    private static void assertRequired(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Required field missing or blank: " + field);
        }
    }
}
