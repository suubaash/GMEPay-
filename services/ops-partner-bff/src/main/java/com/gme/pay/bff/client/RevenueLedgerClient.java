package com.gme.pay.bff.client;

import com.gme.pay.bff.web.dto.JournalPage;

import java.math.BigDecimal;
import java.time.Instant;
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

    /**
     * Lists posted double-entry journals (with their DR/CR lines) for the Admin UI journal view,
     * proxying revenue-ledger's {@code GET /v1/journals}. All args optional (null = upstream default:
     * last 30 days, size cap 200); the returned {@link JournalPage} carries the same shape upstream
     * produced. {@code from}/{@code to} are ISO-8601 instants bounding {@code createdAt}.
     *
     * <p>Default is an empty page so pre-existing anonymous {@link RevenueLedgerClient} impls (test
     * doubles) stay source-compatible; the Rest and Stub beans override it.
     */
    default JournalPage listJournals(Instant from, Instant to, String reference, Integer page, Integer size) {
        return new JournalPage(java.util.List.of(), page == null ? 0 : page, size == null ? 50 : size, 0L);
    }

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
