package com.gme.pay.settlement.calculator;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Per-merchant result of NET (domestic) settlement calculation.
 *
 * <p>Formula:
 * <pre>
 *   merchantFeeTotal    = SUM(ROUND(target_payout * fee_rate, 0))  [KRW, HALF_UP]
 *   netSettlementAmount = grossTxnAmount - merchantFeeTotal
 * </pre>
 */
public record NetSettlementSummary(
        String merchantId,
        LocalDate settlementDate,
        int grossTxnCount,
        BigDecimal grossTxnAmount,       // KRW, integer scale
        BigDecimal merchantFeeTotal,     // KRW, integer scale
        BigDecimal netSettlementAmount   // KRW, integer scale
) {

    /** Settlement type is always 'N' for NET summaries. */
    public char settlementType() {
        return 'N';
    }
}
