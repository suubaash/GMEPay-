package com.gme.pay.bff.web.dto;

import com.gme.pay.contracts.BalanceView;

import java.time.LocalDate;

/**
 * Aggregated landing-page view for the Partner Self-Service Portal. Composed
 * from prefunding (balance), transaction-mgmt (recent activity counter) and
 * settlement-reconciliation (last settled date).
 *
 * <p>UC-10-01: {@code balance} uses the canonical {@link BalanceView} which includes
 * {@code pctOfThreshold} and (when available) the recent deduction history. Internal
 * revenue fields are never present — they only exist in the Admin revenue surface.
 */
public record PartnerOverview(
        String partnerId,
        BalanceView balance,
        int recentTxnCount,
        LocalDate lastSettlementDate
) {}
