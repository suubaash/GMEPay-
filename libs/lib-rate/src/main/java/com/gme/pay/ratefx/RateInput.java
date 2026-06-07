package com.gme.pay.ratefx;

import java.math.BigDecimal;

/**
 * Inputs to the RECEIVE-mode (payout-first) rate calculation (RATE-04 §3).
 * Currencies are ISO-4217. Treasury cost rates follow the convention usd_{ccy} =
 * units of {ccy} per 1 USD. Margins m_a / m_b are fractions (0.01 = 1%).
 */
public record RateInput(
        BigDecimal targetPayout,
        String collectionCurrency,
        String settleACurrency,
        String settleBCurrency,
        String payoutCurrency,
        BigDecimal costRateColl,
        BigDecimal costRatePay,
        BigDecimal mA,
        BigDecimal mB,
        BigDecimal serviceCharge) {
}
