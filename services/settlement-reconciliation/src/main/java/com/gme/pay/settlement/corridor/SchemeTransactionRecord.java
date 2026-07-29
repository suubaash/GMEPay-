package com.gme.pay.settlement.corridor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Leg (a) of the cross-border three-way tie-out: <b>our own transaction record</b>, as
 * transaction-mgmt reports it.
 *
 * <p>Deliberately a separate projection from {@link com.gme.pay.settlement.model.TransactionRecord}
 * (the ZeroPay/KRW settlement projection): the cross-border tie-out needs the FX and USD fields
 * that projection drops — {@code prefundingDeductedUsd} above all, because it is the USD figure
 * the hub actually moved out of the partner float.
 *
 * @param txnRef                transaction-mgmt's own reference
 * @param partnerRef            the hub partner reference (e.g. {@code SENDMN-<uuid>}) — the JOIN KEY
 *                              across all three legs: prefunding keyed its deduct on it and the
 *                              SendMN adapter persisted it as {@code smn_payments.hub_reference}
 * @param merchantId            scheme merchant id
 * @param schemeId              e.g. {@code sendmn}
 * @param sendCcy               currency the wallet was charged in (KRW on this corridor)
 * @param sendAmount            amount charged in {@code sendCcy}, EXCLUDING the hub service fee
 *                              (the fee is added on top before the USD conversion)
 * @param targetCcy             payout currency (MNT)
 * @param targetPayout          amount paid to the merchant in {@code targetCcy}
 * @param prefundingDeductedUsd USD deducted from the partner float for this payment; null until the
 *                              txn is APPROVED (or on a row the hub failed to commit)
 * @param status                transaction status (APPROVED for settlement rows)
 * @param approvedAt            scheme approval instant; null when never approved
 */
public record SchemeTransactionRecord(
        String txnRef,
        String partnerRef,
        String merchantId,
        String schemeId,
        String sendCcy,
        BigDecimal sendAmount,
        String targetCcy,
        BigDecimal targetPayout,
        BigDecimal prefundingDeductedUsd,
        String status,
        Instant approvedAt) {

    /** The tie-out join key: the hub partner reference, falling back to txnRef when absent. */
    public String joinKey() {
        return partnerRef != null && !partnerRef.isBlank() ? partnerRef : txnRef;
    }
}
