package com.gme.pay.settlement.corridor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Leg (c) of the cross-border three-way tie-out: <b>the scheme adapter's own record of what the
 * scheme confirmed</b> — for SENDMN, one {@code smn_payments} row in status APPROVED together with
 * the SendMN-<em>registered</em> rate the Confirm was computed at.
 *
 * <p>{@code settlementUsd} is the amount GME <b>owes the scheme</b>: local amount / registered rate,
 * the figure SendMN itself re-verifies (error 307). The hub's USD deduction was priced on a
 * different basis entirely (live USD/KRW, or the hardcoded fallback), and the difference between
 * the two is the rate-basis variance this tie-out exists to expose.
 *
 * <p>This record is also the type a REAL partner settlement/recon file would parse into once the
 * format is known — see {@link SchemeReconFeedParser}. Nothing here is partner-supplied today.
 *
 * @param reference       hub partner reference (the join key; null on rows written before the
 *                        adapter persisted it)
 * @param schemeRef       the scheme's own id for the payment (SendMN TX_TOKEN_NO)
 * @param merchantId      merchant the scheme paid (carried so a scheme-only break still names a
 *                        merchant in the ops queue)
 * @param localCcy        local payout currency (MNT)
 * @param localAmount     local amount the scheme paid the merchant
 * @param registeredRate  the scheme-registered rate the settlement amount was computed at
 *                        (MNT per USD)
 * @param settlementCcy   settlement currency owed to the scheme (USD)
 * @param settlementUsd   USD owed to the scheme = localAmount / registeredRate
 * @param status          adapter status (APPROVED for settlement rows)
 * @param confirmedAt     instant the adapter recorded the confirmation
 */
public record SchemeSettlementRecord(
        String reference,
        String schemeRef,
        String merchantId,
        String localCcy,
        BigDecimal localAmount,
        BigDecimal registeredRate,
        String settlementCcy,
        BigDecimal settlementUsd,
        String status,
        Instant confirmedAt) {
}
