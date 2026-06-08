package com.gme.pay.payment.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Request body for POST /v1/payments/{id}/cancel. */
public record CancelPaymentRequest(
        @JsonProperty("reason")        String reason,
        @JsonProperty("reason_detail") String reasonDetail
) {}
