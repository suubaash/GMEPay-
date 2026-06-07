package com.gme.pay.ratefx;

import java.math.BigDecimal;

/**
 * Output of the rate engine. All USD-pool values and derived rates are recorded and,
 * at commit, permanently locked onto the transaction (RATE-04 §9).
 */
public record RateResult(
        BigDecimal payoutUsdCost,
        BigDecimal collectionUsd,
        BigDecimal collectionMarginUsd,
        BigDecimal payoutMarginUsd,
        BigDecimal sendAmount,
        BigDecimal collectionAmount,
        BigDecimal offerRateColl,
        BigDecimal crossRate,
        boolean shortCircuit) {
}
