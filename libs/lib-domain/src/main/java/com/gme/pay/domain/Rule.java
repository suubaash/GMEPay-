package com.gme.pay.domain;

import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import java.math.BigDecimal;

/**
 * A Rule is the join (partner x scheme x direction) carrying the margins, settlement currencies,
 * and flat service charge used by the rate engine. Adding a rule is configuration, never code.
 */
public record Rule(
        String partnerId,
        String schemeId,
        Direction direction,
        String settleACurrency,
        String settleBCurrency,
        BigDecimal mA,
        BigDecimal mB,
        BigDecimal serviceCharge) {

    private static final BigDecimal MIN_COMBINED_MARGIN = new BigDecimal("0.02");

    public boolean sameCurrency() {
        return settleACurrency != null && settleACurrency.equalsIgnoreCase(settleBCurrency);
    }

    public BigDecimal combinedMargin() {
        return nz(mA).add(nz(mB));
    }

    /**
     * Enforces the margin rule (RATE-04 §11): cross-border rules need m_a + m_b >= 2%;
     * same-currency rules must have zero margin (the USD pool is short-circuited).
     */
    public void validate() {
        BigDecimal combined = combinedMargin();
        if (sameCurrency()) {
            if (combined.signum() != 0) {
                throw new ApiException(ErrorCode.MIN_MARGIN_VIOLATION,
                        "same-currency rule must have zero combined margin, was " + combined);
            }
        } else if (combined.compareTo(MIN_COMBINED_MARGIN) < 0) {
            throw new ApiException(ErrorCode.MIN_MARGIN_VIOLATION,
                    "combined margin " + combined + " < 2% for a cross-border rule");
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
