package com.gme.pay.payment.web.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Response body for GET /v1/balance — an OVERSEAS partner's prefunding balance inquiry
 * (API-05 §4.8, backlog 5.2-T27).
 *
 * <p>Money fields serialize as decimal <em>strings</em> ({@link JsonFormat.Shape#STRING}) per
 * {@code docs/MONEY_CONVENTION.md} — never JSON numbers. {@code lowBalanceThresholdUsd} is omitted
 * when no threshold is configured.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PrefundingBalanceResponse(
        @JsonProperty("partner_id")               long partnerId,
        @JsonProperty("balance_usd")
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal balanceUsd,
        @JsonProperty("low_balance_threshold_usd")
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal lowBalanceThresholdUsd,
        @JsonProperty("is_below_threshold")       boolean isBelowThreshold,
        @JsonProperty("as_of")                    Instant asOf
) {}
