package com.gme.sim.merchant.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * Request for POST /v1/merchant/zeropay/{merchantId}/dynamic-qr.
 * KRW has no minor units, so the amount must be a whole number of won.
 */
public record ZeroPayChargeRequest(
        @NotNull @Positive BigDecimal amount
) {}
