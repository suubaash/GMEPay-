package com.gme.pay.payment.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request body for POST /v1/payments/cpm/generate.
 * Triggers CPM QR token generation at the scheme.
 */
public record CpmGenerateRequest(
        @JsonProperty("quote_id")        String quoteId,
        @JsonProperty("scheme_id")       String schemeId,
        @JsonProperty("direction")       String direction,
        @JsonProperty("partner_txn_ref") String partnerTxnRef,
        @JsonProperty("collection_amount")   String collectionAmount,
        @JsonProperty("collection_currency") String collectionCurrency
) {
    public void validate() {
        assertRequired("quote_id", quoteId);
        assertRequired("scheme_id", schemeId);
        assertRequired("direction", direction);
        assertRequired("partner_txn_ref", partnerTxnRef);
        assertRequired("collection_amount", collectionAmount);
        assertRequired("collection_currency", collectionCurrency);
    }

    private static void assertRequired(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Required field missing or blank: " + field);
        }
    }
}
