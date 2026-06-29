package com.gme.pay.payment.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;

/** Response body for POST /v1/payments/{id}/refund (full reversal of an APPROVED txn). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RefundPaymentResponse(
        @JsonProperty("payment_id")           String paymentId,
        @JsonProperty("status")               String status,
        @JsonProperty("refunded_at")          Instant refundedAt,
        @JsonProperty("prefund_returned_usd") BigDecimal prefundReturnedUsd
) {}
