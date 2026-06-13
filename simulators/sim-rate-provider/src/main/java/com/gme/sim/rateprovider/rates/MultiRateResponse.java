package com.gme.sim.rateprovider.rates;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Response for GET /v1/rates?base=X (no quote param).
 * {@code quotes} values are BigDecimal strings.
 */
public record MultiRateResponse(
        @JsonProperty("base")   String base,
        @JsonProperty("asOf")   String asOf,
        @JsonProperty("source") String source,
        @JsonProperty("quotes") Map<String, String> quotes   // ccy -> rate string
) {}
