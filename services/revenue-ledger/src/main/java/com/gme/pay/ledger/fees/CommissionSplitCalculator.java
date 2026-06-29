package com.gme.pay.ledger.fees;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

/**
 * Computes the <b>two-sided</b> commission split for one transaction from the
 * configurable shares (V031): the scheme-side GME↔scheme split (delegated to
 * {@link SchemeFeeSplitCalculator}) followed by the partner-side GME↔partner
 * split. There is <b>no hardcoded 70/30</b> — both {@code gmeSharePct} and
 * {@code partnerSharePct} are resolved from config-registry
 * (scheme/partner commission setup).
 *
 * <h2>Formula chain (all KRW, floored)</h2>
 * <pre>
 *   // split 1 — scheme (SchemeFeeSplitCalculator)
 *   gross  = floor(payout × merchantFeeRate)
 *   van    = floor(payout × vanFeeRate)
 *   net    = gross − van
 *   gmeGross = floor(net × gmeSharePct)        // GME's cut of the net fee
 *   scheme   = net − gmeGross                  // scheme operator's cut (remainder)
 *
 *   // split 2 — partner (here)
 *   partner = floor(gmeGross × partnerSharePct) // wallet partner's cut of GME's commission
 *   gmeNet  = gmeGross − partner                // GME's retained commission (remainder)
 * </pre>
 *
 * <h2>Worked example</h2>
 * payout=100 000, merchant=2.00%, van=0.20%, gmeShare=0.70, partnerShare=0.30:
 * gross=2000, van=200, net=1800, gmeGross=1260, scheme=540, partner=378
 * (floor(1260×0.30)), gmeNet=882.
 *
 * <h2>Why remainders?</h2>
 * Each split's second amount is the remainder, never a second flooring, so the
 * invariants {@code gmeGross + scheme == net} and
 * {@code partner + gmeNet == gmeGross} hold exactly — no KRW is created or lost.
 *
 * @see SchemeFeeSplitCalculator
 * @see CommissionSplit
 */
@Component
public class CommissionSplitCalculator {

    private final SchemeFeeSplitCalculator schemeSplit;

    public CommissionSplitCalculator(SchemeFeeSplitCalculator schemeSplit) {
        this.schemeSplit = schemeSplit;
    }

    /**
     * Compute the full two-sided commission split.
     *
     * @param payoutAmountKrw  target payout in KRW (&ge; 0)
     * @param merchantFeeRate  gross merchant fee rate, e.g. {@code 0.0080} (&ge; 0)
     * @param vanFeeRate       VAN intermediary rate, e.g. {@code 0.0008}
     *                         (&ge; 0 and &lt; merchantFeeRate)
     * @param gmeSharePct      GME's configured share of the net fee, in {@code (0,1]}
     * @param partnerSharePct  partner's configured share of GME's commission, in {@code [0,1]}
     * @return the {@link CommissionSplit}; both split invariants always hold
     * @throws IllegalArgumentException if any guard is violated
     * @throws ArithmeticException      if a split invariant is unexpectedly broken
     */
    public CommissionSplit calculate(long payoutAmountKrw,
                                     BigDecimal merchantFeeRate,
                                     BigDecimal vanFeeRate,
                                     BigDecimal gmeSharePct,
                                     BigDecimal partnerSharePct) {

        if (partnerSharePct == null
                || partnerSharePct.compareTo(BigDecimal.ZERO) < 0
                || partnerSharePct.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException(
                    "partnerSharePct must be in [0, 1], got: " + partnerSharePct);
        }

        // Split 1 — scheme side (reuses the audited, test-vectored engine).
        FeeShareResult scheme = schemeSplit.calculate(
                payoutAmountKrw, merchantFeeRate, vanFeeRate, gmeSharePct);
        long gmeGross = scheme.gmeFeeShareKrw();

        // Split 2 — partner side. Floor the partner cut; GME keeps the remainder.
        long partner = BigDecimal.valueOf(gmeGross)
                .multiply(partnerSharePct)
                .setScale(0, RoundingMode.FLOOR)
                .longValueExact();
        long gmeNet = gmeGross - partner;

        if (partner + gmeNet != gmeGross) {
            throw new ArithmeticException(
                    "Invariant violation: partner(" + partner + ") + gmeNet(" + gmeNet
                            + ") != gmeGross(" + gmeGross + ")");
        }

        return new CommissionSplit(
                scheme.grossMerchantFeeKrw(),
                scheme.vanFeeKrw(),
                scheme.netMerchantFeeKrw(),
                scheme.zeropayFeeShareKrw(),
                gmeGross,
                partner,
                gmeNet);
    }
}
