package com.gme.pay.settlement.recon;

import java.math.BigDecimal;

/**
 * A single line in the reconciliation result.
 * One entry per merchant (or per transaction for detail-level recon).
 */
public record ReconLine(
        String merchantId,
        BigDecimal gmeAmount,        // GME's expected net_settlement_amount (KRW)
        BigDecimal schemeAmount,     // Amount confirmed by ZeroPay (KRW), null = missing
        BigDecimal discrepancyAmount,
        MatchStatus matchStatus
) {

    /** Convenience: line is unmatched (needs ops attention). */
    public boolean requiresAttention() {
        return matchStatus != MatchStatus.MATCHED;
    }
}
