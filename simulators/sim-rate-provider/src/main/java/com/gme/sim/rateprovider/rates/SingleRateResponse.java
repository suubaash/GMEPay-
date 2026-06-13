package com.gme.sim.rateprovider.rates;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response for GET /v1/rates?base=X&quote=Y.
 * {@code rate} is always a BigDecimal serialised as a JSON string.
 */
public record SingleRateResponse(
        @JsonProperty("base")  String base,
        @JsonProperty("quote") String quote,
        @JsonProperty("rate")  String rate,   // BigDecimal as string
        @JsonProperty("asOf")  String asOf,
        @JsonProperty("source") String source
) {}
