package com.gme.pay.reporting.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Immutable projection of a committed transaction, as consumed by this service
 * from the transaction-mgmt API.  This is NOT a JPA entity — it is this service's
 * own value object populated from the remote API response DTO.
 *
 * <p>Key BOK fields:
 * <ul>
 *   <li>{@code offerRateColl} — BOK FX1015 field #14 =
 *       send_amount / (collection_usd - collection_margin_usd).
 *       NULL for same-currency short-circuit transactions.</li>
 *   <li>{@code crossRate} — target_payout / send_amount.
 *       NULL for same-currency short-circuit transactions.</li>
 * </ul>
 */
public final class CommittedTransaction {

    private final long txnId;
    private final String txnRef;
    private final TransactionDirection direction;
    private final boolean sameCcyShortcircuit;

    /** send_amount / (collection_usd - collection_margin_usd). BOK FX1015 field #14. */
    private final BigDecimal offerRateColl;

    /** target_payout / send_amount. */
    private final BigDecimal crossRate;

    private final BigDecimal collectionAmount;
    private final String collectionCcy;
    private final BigDecimal payoutAmount;
    private final String payoutCcy;
    private final BigDecimal usdAmount;
    private final Instant committedAt;
    private final long partnerId;

    public CommittedTransaction(
            long txnId,
            String txnRef,
            TransactionDirection direction,
            boolean sameCcyShortcircuit,
            BigDecimal offerRateColl,
            BigDecimal crossRate,
            BigDecimal collectionAmount,
            String collectionCcy,
            BigDecimal payoutAmount,
            String payoutCcy,
            BigDecimal usdAmount,
            Instant committedAt,
            long partnerId) {
        this.txnId = txnId;
        this.txnRef = txnRef;
        this.direction = direction;
        this.sameCcyShortcircuit = sameCcyShortcircuit;
        this.offerRateColl = offerRateColl;
        this.crossRate = crossRate;
        this.collectionAmount = collectionAmount;
        this.collectionCcy = collectionCcy;
        this.payoutAmount = payoutAmount;
        this.payoutCcy = payoutCcy;
        this.usdAmount = usdAmount;
        this.committedAt = committedAt;
        this.partnerId = partnerId;
    }

    public long getTxnId() { return txnId; }
    public String getTxnRef() { return txnRef; }
    public TransactionDirection getDirection() { return direction; }
    public boolean isSameCcyShortcircuit() { return sameCcyShortcircuit; }
    /** BOK FX1015 field #14. Null for same-currency transactions. */
    public BigDecimal getOfferRateColl() { return offerRateColl; }
    public BigDecimal getCrossRate() { return crossRate; }
    public BigDecimal getCollectionAmount() { return collectionAmount; }
    public String getCollectionCcy() { return collectionCcy; }
    public BigDecimal getPayoutAmount() { return payoutAmount; }
    public String getPayoutCcy() { return payoutCcy; }
    public BigDecimal getUsdAmount() { return usdAmount; }
    public Instant getCommittedAt() { return committedAt; }
    public long getPartnerId() { return partnerId; }
}
