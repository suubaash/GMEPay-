package com.gme.pay.settlement.web;

import java.time.LocalDate;

/**
 * Query parameters for GET /v1/settlements.
 * All parameters are optional filters.
 */
public record SettlementQueryRequest(
        LocalDate settlementDate,   // filter by date; null = today
        String merchantId,          // filter by merchant; null = all
        Character settlementType    // 'N' or 'G'; null = all
) {}
