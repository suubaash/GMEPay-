package com.gme.pay.ledger.fees;

/**
 * Immutable result of the 70/30 scheme fee-share split computation.
 * All amounts are in KRW (BIGINT, 0 decimals).
 *
 * <p>Invariant (always holds): {@code gmeFeeShareKrw + zeropayFeeShareKrw == netMerchantFeeKrw}
 *
 * @param grossMerchantFeeKrw floor(payoutAmountKrw x merchantFeeRate)
 * @param vanFeeKrw           floor(payoutAmountKrw x vanFeeRate)
 * @param netMerchantFeeKrw   grossMerchantFeeKrw - vanFeeKrw
 * @param gmeFeeShareKrw      floor(netMerchantFeeKrw x gmeFeeSharePct)  — GME's 70%
 * @param zeropayFeeShareKrw  netMerchantFeeKrw - gmeFeeShareKrw         — ZeroPay's 30%
 */
public record FeeShareResult(
        long grossMerchantFeeKrw,
        long vanFeeKrw,
        long netMerchantFeeKrw,
        long gmeFeeShareKrw,
        long zeropayFeeShareKrw
) {}
