package com.gme.pay.bff.web.dto;

import com.gme.pay.bff.client.PrefundingClient;

import java.time.LocalDate;

/**
 * Aggregated landing-page view for the Partner Self-Service Portal. Composed
 * from prefunding (balance), transaction-mgmt (recent activity counter) and
 * settlement-reconciliation (last settled date).
 */
public record PartnerOverview(
        String partnerId,
        PrefundingClient.BalanceView balance,
        int recentTxnCount,
        LocalDate lastSettlementDate
) {}
