package com.gme.pay.ledger.fees;

/**
 * Immutable result of the <b>two-sided</b> commission split for a single
 * transaction. All amounts are in KRW (BIGINT, 0 decimals).
 *
 * <p>The net merchant fee is shared twice, both splits driven by configurable
 * shares (V031; there is no fixed 70/30):
 *
 * <ol>
 *   <li><b>Scheme split</b> — the net merchant fee splits between GME and the
 *       scheme operator by the scheme's configured {@code gmeSharePct}:
 *       {@code gmeGrossShareKrw + schemeShareKrw == netMerchantFeeKrw}.</li>
 *   <li><b>Partner split</b> — GME's gross share is then split between the
 *       wallet partner and GME by the partner's configured
 *       {@code partnerSharePct}:
 *       {@code partnerShareKrw + gmeNetShareKrw == gmeGrossShareKrw}.</li>
 * </ol>
 *
 * <p>Both invariants always hold exactly because the second amount of each
 * split is computed as the remainder, not a second flooring.
 *
 * @param grossMerchantFeeKrw floor(payoutAmountKrw × merchantFeeRate)
 * @param vanFeeKrw           floor(payoutAmountKrw × vanFeeRate)
 * @param netMerchantFeeKrw   grossMerchantFeeKrw − vanFeeKrw
 * @param schemeShareKrw      scheme operator's cut of the net fee (remainder of split 1)
 * @param gmeGrossShareKrw    GME's cut of the net fee, BEFORE the partner carve
 *                            (= floor(netMerchantFeeKrw × gmeSharePct))
 * @param partnerShareKrw     wallet partner's cut of GME's commission
 *                            (= floor(gmeGrossShareKrw × partnerSharePct))
 * @param gmeNetShareKrw      GME's retained commission, AFTER the partner carve
 *                            (remainder of split 2)
 */
public record CommissionSplit(
        long grossMerchantFeeKrw,
        long vanFeeKrw,
        long netMerchantFeeKrw,
        long schemeShareKrw,
        long gmeGrossShareKrw,
        long partnerShareKrw,
        long gmeNetShareKrw
) {}
