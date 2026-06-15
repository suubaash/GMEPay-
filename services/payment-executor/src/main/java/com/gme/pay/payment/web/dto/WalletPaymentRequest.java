package com.gme.pay.payment.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * Request body for POST /v1/pay — the GMERemit wallet-facing payment entry point.
 *
 * <p>The wallet scans the merchant QR code, passes the raw payload here with the KRW amount
 * it wishes to pay. No rate-quote is needed because GMEREMIT domestic payments are KRW→KRW
 * (no FX). The ₩500 fixed service fee is calculated server-side and returned in the response.
 */
public record WalletPaymentRequest(
        /** Raw EMVCo QR string scanned from the merchant terminal. */
        @JsonProperty("qrPayload")      String qrPayload,
        /** Amount in Korean Won the payer intends to send (as a decimal string per money convention). */
        @JsonProperty("amountKrw")      String amountKrw,
        /** Originating partner identifier — must be "GMEREMIT" for this endpoint. */
        @JsonProperty("partner")        String partner,
        /** Wallet user reference (e.g. wallet account ID or user UUID). */
        @JsonProperty("userRef")        String userRef
) {
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
    }

    private static void assertRequired(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Required field missing or blank: " + field);
        }
    }
}
