package com.gme.pay.settlement.web;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Response DTO for GET /v1/settlements.
 * One entry per settlement batch summary (per merchant per settlement date).
 */
public record SettlementResponse(
        String merchantId,
        LocalDate settlementDate,
        char settlementType,           // 'N' = NET domestic, 'G' = GROSS international
        int txnCount,
        BigDecimal grossTxnAmount,     // KRW, integer
        BigDecimal merchantFeeTotal,   // KRW, integer; 0 for GROSS
        BigDecimal netSettlementAmount // KRW, integer; == grossTxnAmount for GROSS
) {}
