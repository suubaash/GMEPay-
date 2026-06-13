package com.gme.pay.ratefx.xe;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Deserialisation model for GET /v1/rates?base=USD from sim-rate-provider.
 * {@code quotes} values are BigDecimal strings per the money convention.
 */
public record XeMultiRateResponse(
        @JsonProperty("base")   String base,
        @JsonProperty("asOf")   String asOf,
        @JsonProperty("source") String source,
        @JsonProperty("quotes") Map<String, String> quotes
) {}
