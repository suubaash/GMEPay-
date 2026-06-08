package com.gme.pay.payment.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;

/** Response body for POST /v1/payments/{id}/cancel. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CancelPaymentResponse(
        @JsonProperty("payment_id")          String paymentId,
        @JsonProperty("status")              String status,
        @JsonProperty("cancelled_at")        Instant cancelledAt,
        @JsonProperty("prefund_returned_usd") BigDecimal prefundReturnedUsd
) {}
