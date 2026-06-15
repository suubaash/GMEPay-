package com.gme.sim.merchant.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Request body for POST /v1/merchant/shops/{merchantId}/charge.
 * amount   – the amount to embed in the dynamic QR (as BigDecimal, JSON string per convention).
 * currency – ISO 4217 currency code; defaults to "KRW" if omitted.
 */
public record ChargeRequest(
        @NotNull BigDecimal amount,
        String currency
) {}
