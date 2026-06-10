package com.gme.pay.bff.client;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * Read-only view of revenue-ledger. Production calls {@code GET /v1/revenue};
 * Phase-1 default is an in-memory stub.
 *
 * <p>Phase C2 adds range-aggregated summary and a multi-axis breakdown so the
 * Admin UI revenue page can render the date-range card and the by-partner /
 * by-scheme / by-currency charts.
 */
public interface RevenueLedgerClient {

    /** Returns the revenue summary for the requested UTC date (today by default). */
    RevenueSummary getSummary(LocalDate date);

    /**
     * Returns the aggregated revenue summary across the inclusive range
     * {@code [from, to]}. The returned record's {@code date} field carries the
     * range upper bound as a convention (so the existing UI fields still bind).
     */
    RevenueSummary summaryRange(LocalDate from, LocalDate to);

    /** Returns the by-partner / by-scheme / by-currency breakdown for the range. */
    RevenueBreakdown breakdown(LocalDate from, LocalDate to);

    record RevenueSummary(
            LocalDate date,
            BigDecimal totalRevenueUsd,
            BigDecimal feeRevenueUsd,
            BigDecimal marginRevenueUsd
    ) {}

    /**
     * Multi-axis breakdown for the Admin UI revenue charts. The maps' keys are
     * partner id / scheme id / ISO-4217 currency code respectively; the values
     * are USD totals.
     */
    record RevenueBreakdown(
            Map<String, BigDecimal> byPartner,
            Map<String, BigDecimal> byScheme,
            Map<String, BigDecimal> byCurrency
    ) {}
}
