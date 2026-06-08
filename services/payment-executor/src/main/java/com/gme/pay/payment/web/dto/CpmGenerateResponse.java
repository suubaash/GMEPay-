package com.gme.pay.payment.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/** Response body for POST /v1/payments/cpm/generate. */
public record CpmGenerateResponse(
        @JsonProperty("payment_id")  String paymentId,
        @JsonProperty("qr_token")    String qrToken,
        @JsonProperty("expires_at")  Instant expiresAt,
        @JsonProperty("scheme_id")   String schemeId
) {}
