package com.gme.pay.ledger.fees;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Computes the ZeroPay scheme fee-share split (70/30) for a single transaction.
 *
 * <h2>Formula chain (all values in KRW, rounded DOWN to 0 decimal places)</h2>
 * <pre>
 *   gross_merchant_fee_krw = floor(payout_amount_krw x merchant_fee_rate)
 *   van_fee_krw            = floor(payout_amount_krw x van_fee_rate)
 *   net_merchant_fee_krw   = gross_merchant_fee_krw - van_fee_krw
 *   gme_fee_share_krw      = floor(net_merchant_fee_krw x gme_fee_share_pct)   // GME 70%
 *   zeropay_fee_share_krw  = net_merchant_fee_krw - gme_fee_share_krw           // ZeroPay 30%
 * </pre>
 *
 * <h2>Worked example</h2>
 * payout=15 000 KRW, merchant_fee_rate=0.80%, van_fee_rate=0.08%, gme_fee_share_pct=0.70:
 * gross=120, van=12, net=108, gme=75 (floor(108×0.70)), zeropay=33.
 *
 * <h2>Why FLOOR?</h2>
 * Using {@link RoundingMode#FLOOR} (truncate towards zero) for every intermediate multiplication
 * ensures amounts are whole KRW and guarantees the invariant
 * {@code gme + zeropay == net} exactly — because zeropay is computed as the remainder rather than
 * a second rounding.
 *
 * <h2>Configuration note</h2>
 * {@code gme_fee_share_pct=0.70} is ZeroPay-specific (ZeroPay retains 30%).
 * Other schemes may configure a different value via {@code qr_scheme.gme_fee_share_pct}.
 * Service charge ({@code service_charge_amount}) is a separate revenue stream and never
 * enters this computation — no double-counting risk.
 *
 * @see FeeShareResult
 */
public class SchemeFeeSplitCalculator {

    /**
     * Compute the scheme fee-share split for one transaction.
     *
     * @param payoutAmountKrw  target payout in KRW (must be &gt;= 0)
     * @param merchantFeeRate  gross merchant fee rate e.g. {@code 0.0080} for 0.80% (must be &gt;= 0)
     * @param vanFeeRate       VAN intermediary fee rate e.g. {@code 0.0008} (must be &gt;= 0 and &lt; merchantFeeRate)
     * @param gmeFeeSharePct   GME's share of the net fee e.g. {@code 0.70} (must be in (0, 1])
     * @return {@link FeeShareResult} with all five KRW amounts; invariant gme+zeropay==net always holds
     * @throws IllegalArgumentException if any guard condition is violated
     * @throws ArithmeticException      if the gme+zeropay==net invariant is unexpectedly broken (belt-and-suspenders)
     */
    public FeeShareResult calculate(long payoutAmountKrw,
                                    BigDecimal merchantFeeRate,
                                    BigDecimal vanFeeRate,
                                    BigDecimal gmeFeeSharePct) {

        // --- Guards ---
        if (payoutAmountKrw < 0) {
            throw new IllegalArgumentException("payoutAmountKrw must be >= 0, got: " + payoutAmountKrw);
        }
        if (merchantFeeRate == null || merchantFeeRate.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("merchantFeeRate must be >= 0");
        }
        if (vanFeeRate == null || vanFeeRate.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("vanFeeRate must be >= 0");
        }
        if (vanFeeRate.compareTo(merchantFeeRate) >= 0 && merchantFeeRate.compareTo(BigDecimal.ZERO) > 0) {
            throw new IllegalArgumentException(
                    "vanFeeRate must be < merchantFeeRate, got van=" + vanFeeRate + " merchant=" + merchantFeeRate);
        }
        if (gmeFeeSharePct == null
                || gmeFeeSharePct.compareTo(BigDecimal.ZERO) <= 0
                || gmeFeeSharePct.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("gmeFeeSharePct must be in (0, 1], got: " + gmeFeeSharePct);
        }

        // --- Computation (all floor / ROUND_DOWN, result in KRW BIGINT) ---
        BigDecimal payout = BigDecimal.valueOf(payoutAmountKrw);

        long gross = payout.multiply(merchantFeeRate).setScale(0, RoundingMode.FLOOR).longValueExact();
        long van   = payout.multiply(vanFeeRate).setScale(0, RoundingMode.FLOOR).longValueExact();
        long net   = gross - van;
        long gme   = BigDecimal.valueOf(net).multiply(gmeFeeSharePct).setScale(0, RoundingMode.FLOOR).longValueExact();
        long zeropay = net - gme;

        // Belt-and-suspenders invariant check (should never fire given floor logic)
        if (gme + zeropay != net) {
            throw new ArithmeticException(
                    "Invariant violation: gme(" + gme + ") + zeropay(" + zeropay + ") != net(" + net + ")");
        }

        return new FeeShareResult(gross, van, net, gme, zeropay);
    }
}
