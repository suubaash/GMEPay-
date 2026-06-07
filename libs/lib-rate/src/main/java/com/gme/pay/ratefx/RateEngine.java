package com.gme.pay.ratefx;

import com.gme.pay.money.CurrencyScale;
import java.math.BigDecimal;
import java.math.MathContext;

/**
 * The GMEPay+ rate engine: USD-volume-based margin, RECEIVE mode (payout-first), 5 steps (RATE-04 §4).
 *
 * <pre>
 * 1. payout_usd_cost      = target_payout / cost_rate_pay
 * 2. collection_usd       = payout_usd_cost / (1 - m_a - m_b)
 * 3. collection_margin_usd = collection_usd * m_a ; payout_margin_usd = collection_usd * m_b
 * 4. send_amount          = collection_usd * cost_rate_coll
 * 5. collection_amount    = send_amount + service_charge   (service_charge never enters the USD pool)
 * </pre>
 *
 * Invariant (pool identity): collection_usd - collection_margin_usd - payout_margin_usd == payout_usd_cost
 * within {@link #IDENTITY_TOLERANCE} USD. Identity legs: a USD settlement leg forces its cost rate to 1.0.
 * Same-currency short-circuit: when all four currencies are equal, the USD pool is skipped entirely.
 */
public final class RateEngine {

    /** Pool-identity tolerance in USD (RATE-04 §5). */
    public static final BigDecimal IDENTITY_TOLERANCE = new BigDecimal("0.01");
    /** Minimum combined margin for cross-border rules (RATE-04 §11). */
    public static final BigDecimal MIN_COMBINED_MARGIN = new BigDecimal("0.02");

    private static final MathContext MC = new MathContext(20);
    private static final BigDecimal ONE = BigDecimal.ONE;

    public RateResult quote(RateInput in) {
        validate(in);

        // Same-currency short-circuit: skip the USD pool entirely.
        if (allSameCurrency(in)) {
            BigDecimal collectionAmount = in.targetPayout().add(nz(in.serviceCharge()));
            return new RateResult(
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    in.targetPayout(), collectionAmount, ONE, ONE, true);
        }

        BigDecimal mA = nz(in.mA());
        BigDecimal mB = nz(in.mB());
        BigDecimal combined = mA.add(mB);
        if (combined.compareTo(MIN_COMBINED_MARGIN) < 0) {
            throw new IllegalArgumentException(
                    "MIN_MARGIN_VIOLATION: m_a + m_b (" + combined + ") < 2% for a cross-border rule");
        }

        // Identity legs: a USD settlement leg has a cost rate of exactly 1.0.
        BigDecimal costRateColl = isUsd(in.settleACurrency()) ? ONE : required(in.costRateColl(), "cost_rate_coll");
        BigDecimal costRatePay = isUsd(in.settleBCurrency()) ? ONE : required(in.costRatePay(), "cost_rate_pay");

        BigDecimal payoutUsdCost = in.targetPayout().divide(costRatePay, MC);            // step 1
        BigDecimal collectionUsd = payoutUsdCost.divide(ONE.subtract(combined), MC);     // step 2
        BigDecimal collectionMarginUsd = collectionUsd.multiply(mA, MC);                 // step 3a
        BigDecimal payoutMarginUsd = collectionUsd.multiply(mB, MC);                     // step 3b
        BigDecimal sendAmount = collectionUsd.multiply(costRateColl, MC);               // step 4
        BigDecimal collectionAmount = sendAmount.add(nz(in.serviceCharge()));           // step 5

        assertPoolIdentity(collectionUsd, collectionMarginUsd, payoutMarginUsd, payoutUsdCost);

        // Derived BOK outputs (never configured).
        BigDecimal offerRateColl = sendAmount.divide(collectionUsd.subtract(collectionMarginUsd), MC);
        BigDecimal crossRate = in.targetPayout().divide(sendAmount, MC);

        return new RateResult(
                payoutUsdCost, collectionUsd, collectionMarginUsd, payoutMarginUsd,
                roundOut(sendAmount, in.settleACurrency()),
                roundOut(collectionAmount, in.settleACurrency()),
                offerRateColl, crossRate, false);
    }

    private void assertPoolIdentity(BigDecimal collectionUsd, BigDecimal collMargin,
                                    BigDecimal payMargin, BigDecimal payoutUsdCost) {
        BigDecimal delta = collectionUsd.subtract(collMargin).subtract(payMargin)
                .subtract(payoutUsdCost).abs();
        if (delta.compareTo(IDENTITY_TOLERANCE) > 0) {
            throw new IllegalStateException(
                    "POOL_IDENTITY_VIOLATION: delta " + delta + " exceeds tolerance " + IDENTITY_TOLERANCE);
        }
    }

    private static BigDecimal roundOut(BigDecimal value, String currency) {
        return CurrencyScale.round(value, currency);
    }

    private void validate(RateInput in) {
        if (in == null || in.targetPayout() == null) {
            throw new IllegalArgumentException("VALIDATION_ERROR: target_payout required");
        }
        if (in.targetPayout().signum() <= 0) {
            throw new IllegalArgumentException("VALIDATION_ERROR: target_payout must be positive");
        }
    }

    private static boolean allSameCurrency(RateInput in) {
        String c = in.collectionCurrency();
        return c != null && c.equalsIgnoreCase(in.settleACurrency())
                && c.equalsIgnoreCase(in.settleBCurrency())
                && c.equalsIgnoreCase(in.payoutCurrency());
    }

    private static boolean isUsd(String ccy) {
        return ccy != null && ccy.equalsIgnoreCase("USD");
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal required(BigDecimal v, String name) {
        if (v == null || v.signum() <= 0) {
            throw new IllegalArgumentException("VALIDATION_ERROR: " + name + " required and must be positive");
        }
        return v;
    }
}
