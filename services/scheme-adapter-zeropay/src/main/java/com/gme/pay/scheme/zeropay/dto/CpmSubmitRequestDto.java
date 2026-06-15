package com.gme.pay.scheme.zeropay.dto;

import java.math.BigDecimal;

/**
 * Request DTO for {@code POST /internal/scheme/zeropay/cpm}.
 *
 * <p>Field names MUST match {@code RestSchemeClient.SchemeCpmSubmitRequest} in payment-executor
 * exactly so Jackson deserialises them without any {@code @JsonProperty} mapping:
 * {@code txnRef}, {@code qrToken}, {@code payoutAmount}, {@code payoutCurrency}, {@code schemeId}.
 */
public record CpmSubmitRequestDto(
        /** Payment-executor internal transaction reference. */
        String txnRef,
        /** CPM token issued by the scheme (from prepareCPM / fetchCpmToken). */
        String qrToken,
        /** Payout amount (BigDecimal serialised as JSON string). */
        BigDecimal payoutAmount,
        /** ISO currency code for the payout (e.g. "KRW"). */
        String payoutCurrency,
        /** Scheme identifier (e.g. "zeropay"). */
        String schemeId
) {}
