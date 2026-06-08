package com.gme.pay.payment.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Response body for a successful POST /v1/payments (Fixed MPM).
 * {@code prefundDeductedUsd} is omitted when null (LOCAL partners).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MpmPaymentResponse(
        @JsonProperty("payment_id")             String paymentId,
        @JsonProperty("status")                 String status,
        @JsonProperty("scheme_txn_id")          String schemeTxnId,
        @JsonProperty("merchant_name")          String merchantName,
        @JsonProperty("merchant_id")            String merchantId,
        @JsonProperty("target_payout")          BigDecimal targetPayout,
        @JsonProperty("payout_currency")        String payoutCurrency,
        @JsonProperty("offer_rate")             BigDecimal offerRate,
        @JsonProperty("collection_amount")      BigDecimal collectionAmount,
        @JsonProperty("collection_currency")    String collectionCurrency,
        @JsonProperty("service_charge")         BigDecimal serviceCharge,
        @JsonProperty("service_charge_currency") String serviceChargeCurrency,
        @JsonProperty("prefund_deducted_usd")   BigDecimal prefundDeductedUsd,
        @JsonProperty("partner_txn_ref")        String partnerTxnRef,
        @JsonProperty("created_at")             Instant createdAt,
        @JsonProperty("approved_at")            Instant approvedAt
) {}
