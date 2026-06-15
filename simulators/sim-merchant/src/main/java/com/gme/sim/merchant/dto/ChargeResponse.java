package com.gme.sim.merchant.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;

/**
 * Response for POST /v1/merchant/shops/{merchantId}/charge.
 * amount is serialized as a JSON string per the BigDecimal-as-string convention.
 */
public record ChargeResponse(
        String mode,
        String qrPayload,
        @JsonSerialize(using = ToStringSerializer.class)
        BigDecimal amount,
        String currency
) {}
