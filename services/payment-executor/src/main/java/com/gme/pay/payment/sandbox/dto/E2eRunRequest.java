package com.gme.pay.payment.sandbox.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request body for {@code POST /v1/sandbox/e2e/run}.
 *
 * <pre>{ "country": "NP", "partner": "GMEREMIT", "amount": "1300", "mpmType": "STATIC" }</pre>
 */
public record E2eRunRequest(
        @JsonProperty("country") String country,
        @JsonProperty("partner") String partner,
        @JsonProperty("amount")  String amount,
        @JsonProperty("mpmType") String mpmType
) {
}
