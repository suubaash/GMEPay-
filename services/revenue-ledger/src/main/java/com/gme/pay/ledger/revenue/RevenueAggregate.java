package com.gme.pay.ledger.revenue;

import java.math.BigDecimal;

/**
 * Aggregated revenue metrics for a partner/scheme over a date range.
 * All monetary totals use COALESCE(0) — never null.
 */
public record RevenueAggregate(
        long partnerId,
        long schemeId,
        long txnCount,
        BigDecimal totalFxMarginUsd,
        BigDecimal totalServiceChargeAmount,
        String serviceChargeCcy
) {}
