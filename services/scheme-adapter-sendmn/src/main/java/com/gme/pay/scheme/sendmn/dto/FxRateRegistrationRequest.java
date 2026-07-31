package com.gme.pay.scheme.sendmn.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * Body of POST /partner-hosted/fx-rate — the endpoint WE host and SENDMN calls to
 * register its settled buy rate (doc §9; the partner designs the contract, so field
 * names mirror the doc's required-field list, UPPER_SNAKE like the rest of the API).
 *
 * @param fxTickerNo        SendMN-generated unique rate registration id
 * @param noticeDate        rate notice date (raw string — format unconfirmed with SendMN)
 * @param rate              MNT per USD buy rate (e.g. 3373.00)
 * @param localCurCode      "MNT" (defaulted when absent)
 * @param settlementCurCode "USD" (defaulted when absent)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FxRateRegistrationRequest(
        @JsonProperty("FX_TICKER_NO") String fxTickerNo,
        @JsonProperty("NOTICE_DATE") String noticeDate,
        @JsonProperty("RATE") BigDecimal rate,
        @JsonProperty("LOCAL_CUR_CODE") String localCurCode,
        @JsonProperty("SETTLEMENT_CUR_CODE") String settlementCurCode
) {}
