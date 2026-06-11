package com.gme.pay.bff.web.dto;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Multi-axis revenue breakdown for the Admin UI revenue charts. The maps' keys
 * are partner id / scheme id / ISO-4217 currency code respectively; the values
 * are USD totals across the requested date range.
 */
public record RevenueBreakdown(
        Map<String, BigDecimal> byPartner,
        Map<String, BigDecimal> byScheme,
        Map<String, BigDecimal> byCurrency
) {}
