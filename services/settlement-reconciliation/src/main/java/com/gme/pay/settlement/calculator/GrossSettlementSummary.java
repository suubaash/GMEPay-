package com.gme.pay.settlement.calculator;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Per-merchant result of GROSS (international) settlement calculation.
 *
 * <p>Formula:
 * <pre>
 *   merchantFeeTotal    = 0  (GME invoices merchant monthly)
 *   netSettlementAmount = grossTxnAmount  (GME remits the full payout)
 * </pre>
 *
 * The 0.21 % ZeroPay scheme share is aggregated monthly via the tax_invoice cycle,
 * not deducted here.
 */
public record GrossSettlementSummary(
        String merchantId,
        LocalDate settlementDate,
        int grossTxnCount,
        BigDecimal grossTxnAmount,       // KRW, integer scale
        BigDecimal merchantFeeTotal,     // always ZERO for GROSS
        BigDecimal netSettlementAmount   // == grossTxnAmount for GROSS
) {

    /** Settlement type is always 'G' for GROSS summaries. */
    public char settlementType() {
        return 'G';
    }
}
