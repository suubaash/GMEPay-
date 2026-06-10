package com.gme.pay.bff.web.dto;

import java.math.BigDecimal;

/**
 * Aggregated landing-page view for the Admin UI. Composed by
 * {@code AdminDashboardController} from one call to each of:
 * config-registry, transaction-mgmt, prefunding (per partner), and
 * revenue-ledger.
 */
public record AdminDashboard(
        int recentTxnCount,
        int partnerCount,
        int lowBalanceCount,
        BigDecimal todayRevenueUsd
) {}
