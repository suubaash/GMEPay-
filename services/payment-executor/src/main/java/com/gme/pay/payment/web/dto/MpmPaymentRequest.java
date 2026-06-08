package com.gme.pay.payment.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request body for POST /v1/payments (Fixed MPM).
 * See API-05 §4.3 for field definitions.
 */
public record MpmPaymentRequest(
        @JsonProperty("quote_id")       String quoteId,
        @JsonProperty("merchant_qr")    String merchantQr,
        @JsonProperty("direction")      String direction,
        @JsonProperty("scheme_id")      String schemeId,
        @JsonProperty("customer_ref")   String customerRef,
        @JsonProperty("partner_txn_ref") String partnerTxnRef,
        @JsonProperty("collection_amount")   String collectionAmount,
        @JsonProperty("collection_currency") String collectionCurrency,
        @JsonProperty("country_code")   String countryCode
) {
    /** Validates that all required fields are non-null/non-blank. */
    public void validate() {
        assertRequired("quote_id", quoteId);
        assertRequired("merchant_qr", merchantQr);
        assertRequired("direction", direction);
        assertRequired("scheme_id", schemeId);
        assertRequired("customer_ref", customerRef);
        assertRequired("partner_txn_ref", partnerTxnRef);
        assertRequired("collection_amount", collectionAmount);
        assertRequired("collection_currency", collectionCurrency);
        if (collectionAmount != null && !collectionAmount.matches("^[0-9]+(\\.[0-9]+)?$")) {
            throw new IllegalArgumentException(
                    "collection_amount must be a valid positive decimal, got: " + collectionAmount);
        }
    }

    private static void assertRequired(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Required field missing or blank: " + field);
        }
    }
}
