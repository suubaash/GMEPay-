package com.gme.sim.merchant.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * Request for POST /v1/merchant/zeropay/{merchantId}/static-result.
 *
 * @param amount     amount the consumer paid against the static QR (whole won)
 * @param approvalNo optional approval number to register against; auto-generated if blank
 */
public record ZeroPayStaticResultRequest(
        @NotNull @Positive BigDecimal amount,
        String approvalNo
) {}
