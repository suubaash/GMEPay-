package com.gme.pay.bff.client;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Read-only view of revenue-ledger. Production calls {@code GET /v1/revenue};
 * Phase-1 default is an in-memory stub.
 */
public interface RevenueLedgerClient {

    /** Returns the revenue summary for the requested UTC date (today by default). */
    RevenueSummary getSummary(LocalDate date);

    record RevenueSummary(
            LocalDate date,
            BigDecimal totalRevenueUsd,
            BigDecimal feeRevenueUsd,
            BigDecimal marginRevenueUsd
    ) {}
}
