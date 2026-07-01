package com.gme.pay.ledger.web;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Response DTO for GET /v1/revenue — aggregate revenue for a partner over a date range.
 * All amounts use snake_case JSON field names for API-05 compatibility.
 */
public record RevenueSummaryResponse(
        long partnerId,
        long schemeId,
        LocalDate startDate,
        LocalDate endDate,
        long txnCount,
        BigDecimal totalFxMarginUsd,
        BigDecimal totalServiceChargeAmount,
        String serviceChargeCcy,
        BigDecimal totalRoundingUsd
) {}
