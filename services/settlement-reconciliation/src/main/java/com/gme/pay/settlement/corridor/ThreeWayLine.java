package com.gme.pay.settlement.corridor;

import com.gme.pay.settlement.recon.MatchStatus;
import com.gme.pay.settlement.recon.ReconLine;

import java.math.BigDecimal;

/**
 * One transaction's outcome in the cross-border three-way tie-out.
 *
 * <p>The three legs, all GME-owned (no partner file involved — see {@link
 * com.gme.pay.settlement.port.SchemeReconFeedParser}):
 * <ol>
 *   <li><b>transaction</b> — what transaction-mgmt recorded ({@code chargedKrw}, {@code localPaid},
 *       {@code usdDeducted});</li>
 *   <li><b>prefunding</b> — the USD actually taken off the partner float ({@code usdMovedPrefunding});</li>
 *   <li><b>scheme</b> — what the adapter recorded the scheme as confirming ({@code localConfirmed},
 *       {@code usdOwedScheme} at {@code registeredRate}).</li>
 * </ol>
 *
 * <p><b>Rate-basis variance</b> ({@code rateBasisVarianceUsd}) is the point of the whole exercise:
 * {@code usdDeducted − usdOwedScheme}, SIGNED. The two figures price the SAME payment on two
 * different rate bases — the hub converts KRW→USD at the live USD/KRW rate (or a hardcoded
 * fallback), while the scheme is owed USD at the MNT/USD rate it registered with us. A positive
 * value is USD retained (the intended fee + FX margin); a NEGATIVE value means GME owes the scheme
 * more USD than it collected, i.e. a real per-payment loss. Null when a leg is missing, because
 * there is then nothing meaningful to subtract.
 *
 * @param reference          hub partner reference — the join key across all three legs
 * @param txnRef             transaction-mgmt's reference (null when only non-txn legs exist)
 * @param merchantId         merchant, best available across legs
 * @param chargedKrw         KRW charged to the wallet incl. the hub service fee; null when unknown
 * @param localCcy           local payout currency (MNT)
 * @param localPaid          local amount per our transaction record
 * @param localConfirmed     local amount per the scheme's confirmation
 * @param usdDeducted        USD deducted per our transaction record
 * @param usdMovedPrefunding USD deducted per the prefunding ledger
 * @param usdOwedScheme      USD owed the scheme at its registered rate
 * @param registeredRate     the scheme-registered rate used (MNT per USD)
 * @param impliedKrwPerUsd   the KRW/USD basis the hub actually priced the deduction at,
 *                           back-derived as {@code chargedKrw / usdDeducted} — this is how a stale
 *                           or fallback rate becomes visible after the fact
 * @param fallbackRateBasis  true when {@code impliedKrwPerUsd} matches the hub's hardcoded fallback
 *                           constant, i.e. the live rate provider was down and the deduction was
 *                           priced off an unverified constant
 * @param rateBasisVarianceUsd signed {@code usdDeducted − usdOwedScheme}; null when incomputable
 * @param matchStatus        classification reused from the ZeroPay lane (plus the two cross-border
 *                           values MISSING_PREFUNDING / RATE_BASIS_VARIANCE)
 * @param note               human-readable reason, carried into the ops queue's resolution note
 */
public record ThreeWayLine(
        String reference,
        String txnRef,
        String merchantId,
        BigDecimal chargedKrw,
        String localCcy,
        BigDecimal localPaid,
        BigDecimal localConfirmed,
        BigDecimal usdDeducted,
        BigDecimal usdMovedPrefunding,
        BigDecimal usdOwedScheme,
        BigDecimal registeredRate,
        BigDecimal impliedKrwPerUsd,
        boolean fallbackRateBasis,
        BigDecimal rateBasisVarianceUsd,
        MatchStatus matchStatus,
        String note) {

    /** True when this line needs ops/finance attention (anything but a clean match). */
    public boolean requiresAttention() {
        return matchStatus != MatchStatus.MATCHED;
    }

    /**
     * Project onto the lane-agnostic {@link ReconLine} so the existing exception queue and
     * {@link com.gme.pay.settlement.alert.ReconBreakAlerter} handle cross-border breaks with no
     * special-casing. Amounts are <b>USD</b> here (the corridor's settlement currency): GME side =
     * USD deducted, scheme side = USD owed, discrepancy = the absolute gap that classified the line.
     *
     * <p>{@code merchantId} is the ReconLine key (it is the ops queue's NOT NULL column); the
     * transaction is carried separately onto the exception row via
     * {@link com.gme.pay.settlement.persistence.ReconExceptionEntity#fromCorridorLine}.
     */
    public ReconLine toReconLine() {
        return new ReconLine(
                merchantId != null && !merchantId.isBlank() ? merchantId : "UNKNOWN",
                usdDeducted != null ? usdDeducted : BigDecimal.ZERO,
                usdOwedScheme,
                discrepancyUsd(),
                matchStatus);
    }

    /**
     * The absolute USD gap this line is judged on: the rate-basis variance when both USD figures
     * exist, else whichever side we do have (a missing leg's whole amount is at risk), else zero.
     */
    public BigDecimal discrepancyUsd() {
        if (rateBasisVarianceUsd != null) {
            return rateBasisVarianceUsd.abs();
        }
        if (usdDeducted != null && usdOwedScheme != null) {
            return usdDeducted.subtract(usdOwedScheme).abs();
        }
        if (usdDeducted != null) {
            return usdDeducted.abs();
        }
        if (usdOwedScheme != null) {
            return usdOwedScheme.abs();
        }
        return BigDecimal.ZERO;
    }
}
