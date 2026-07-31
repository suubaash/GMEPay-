package com.gme.pay.settlement.corridor;

import java.math.BigDecimal;

/**
 * Everything corridor-specific the three-way tie-out needs, so
 * {@link CorridorThreeWayReconciler} itself carries no scheme knowledge and a second corridor is a
 * second bean rather than a second engine.
 *
 * <p>Only SENDMN is specified today (see {@link CorridorReconConfig}). 9Pay is deliberately absent:
 * it has no hub payout orchestration yet (register item T4-7, awaiting a product decision), so there
 * are no hub-side 9Pay payouts to reconcile and any spec for it would be speculation.
 *
 * @param scheme                upper-case recon lane code stamped on exceptions/summaries (SENDMN)
 * @param schemeId              scheme id as transaction-mgmt records it ({@code sendmn})
 * @param partnerCode           prefunding partner code whose float this corridor draws on (SENDMN)
 * @param corridor              display label for the summary, e.g. {@code KRW->MNT}
 * @param localCcy              local payout currency (MNT)
 * @param serviceFeeKrw         fixed KRW service fee the hub adds on top of the wallet amount before
 *                              converting to USD. Needed to reconstruct {@code chargedKrw} — the hub
 *                              deducts USD for (amount + fee), while transaction-mgmt stores the
 *                              amount only. Mirrors payment-executor's constant; a mismatch shows up
 *                              as a wrong {@code impliedKrwPerUsd}, not as silent drift.
 * @param fallbackKrwPerUsd     the hub's hardcoded USD/KRW fallback constant. A back-derived basis
 *                              equal to this (within {@code fallbackMatchToleranceKrw}) means the
 *                              deduction was priced off an unverified constant, not a live rate.
 * @param fallbackMatchToleranceKrw tolerance for the fallback comparison, in KRW per USD
 * @param rateBasisToleranceUsd USD shortfall tolerated before a line is classified
 *                              {@code RATE_BASIS_VARIANCE}. 0 = any shortfall breaks.
 */
public record CorridorSpec(
        String scheme,
        String schemeId,
        String partnerCode,
        String corridor,
        String localCcy,
        BigDecimal serviceFeeKrw,
        BigDecimal fallbackKrwPerUsd,
        BigDecimal fallbackMatchToleranceKrw,
        BigDecimal rateBasisToleranceUsd) {

    /** Stable recon-run id for a settlement date, e.g. {@code SENDMN-3WAY-20260728}. */
    public String batchId(java.time.LocalDate settlementDate) {
        return scheme + "-3WAY-"
                + settlementDate.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
    }
}
