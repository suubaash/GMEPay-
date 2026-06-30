package com.gme.pay.payment.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Response body for GET /v1/payments/{id} — the full payment record for partner status retrieval
 * (API-05 §4, backlog 5.2-T16).
 *
 * <p>{@code status} is the lowercase API status (pending, approved, failed, cancelled, uncertain).
 * {@code prefundDeductedUsd} is emitted only for OVERSEAS partners with a non-null reserved/captured
 * USD; {@code approvedAt}/{@code cancelledAt} are null until the corresponding transition. All
 * null-valued fields are omitted from the JSON ({@link JsonInclude.Include#NON_NULL}) so a LOCAL
 * payment never carries {@code prefund_deducted_usd}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaymentDetailResponse(
        @JsonProperty("payment_id")           String paymentId,
        @JsonProperty("status")               String status,
        @JsonProperty("partner_txn_ref")      String partnerTxnRef,
        @JsonProperty("scheme_id")            String schemeId,
        @JsonProperty("direction")            String direction,
        @JsonProperty("merchant_id")          String merchantId,
        @JsonProperty("merchant_name")        String merchantName,
        @JsonProperty("target_payout")        BigDecimal targetPayout,
        @JsonProperty("payout_currency")      String payoutCurrency,
        @JsonProperty("collection_amount")    BigDecimal collectionAmount,
        @JsonProperty("collection_currency")  String collectionCurrency,
        @JsonProperty("service_charge")       BigDecimal serviceCharge,
        @JsonProperty("prefund_deducted_usd") BigDecimal prefundDeductedUsd,
        @JsonProperty("created_at")           Instant createdAt,
        @JsonProperty("approved_at")          Instant approvedAt,
        @JsonProperty("cancelled_at")         Instant cancelledAt
) {}
