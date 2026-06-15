package com.gme.pay.reporting.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * Wire DTO for a single transaction record from the transaction-mgmt service.
 *
 * <p>Field names match transaction-mgmt's {@code GET /v1/transactions} response exactly.
 * Jackson binds by {@code @JsonProperty} names; any mismatch silently produces null.
 *
 * <p>Money fields are received as JSON strings (BigDecimal-as-string contract).
 */
public class TransactionRecord {

    @JsonProperty("txn_id")
    private long txnId;

    @JsonProperty("txn_ref")
    private String txnRef;

    /** Values: INBOUND, OUTBOUND, DOMESTIC, HUB */
    @JsonProperty("direction")
    private String direction;

    @JsonProperty("same_ccy_shortcircuit")
    private boolean sameCcyShortcircuit;

    /** BOK FX1015 field #14. Null for same-currency short-circuit transactions. */
    @JsonProperty("offer_rate_coll")
    private BigDecimal offerRateColl;

    @JsonProperty("cross_rate")
    private BigDecimal crossRate;

    @JsonProperty("collection_amount")
    private BigDecimal collectionAmount;

    @JsonProperty("collection_ccy")
    private String collectionCcy;

    @JsonProperty("payout_amount")
    private BigDecimal payoutAmount;

    @JsonProperty("payout_ccy")
    private String payoutCcy;

    @JsonProperty("usd_amount")
    private BigDecimal usdAmount;

    /** ISO-8601 instant string, e.g. "2026-01-15T10:00:00Z". */
    @JsonProperty("committed_at")
    private String committedAt;

    @JsonProperty("partner_id")
    private long partnerId;

    public TransactionRecord() {}

    public long getTxnId() { return txnId; }
    public void setTxnId(long txnId) { this.txnId = txnId; }

    public String getTxnRef() { return txnRef; }
    public void setTxnRef(String txnRef) { this.txnRef = txnRef; }

    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }

    public boolean isSameCcyShortcircuit() { return sameCcyShortcircuit; }
    public void setSameCcyShortcircuit(boolean sameCcyShortcircuit) { this.sameCcyShortcircuit = sameCcyShortcircuit; }

    public BigDecimal getOfferRateColl() { return offerRateColl; }
    public void setOfferRateColl(BigDecimal offerRateColl) { this.offerRateColl = offerRateColl; }

    public BigDecimal getCrossRate() { return crossRate; }
    public void setCrossRate(BigDecimal crossRate) { this.crossRate = crossRate; }

    public BigDecimal getCollectionAmount() { return collectionAmount; }
    public void setCollectionAmount(BigDecimal collectionAmount) { this.collectionAmount = collectionAmount; }

    public String getCollectionCcy() { return collectionCcy; }
    public void setCollectionCcy(String collectionCcy) { this.collectionCcy = collectionCcy; }

    public BigDecimal getPayoutAmount() { return payoutAmount; }
    public void setPayoutAmount(BigDecimal payoutAmount) { this.payoutAmount = payoutAmount; }

    public String getPayoutCcy() { return payoutCcy; }
    public void setPayoutCcy(String payoutCcy) { this.payoutCcy = payoutCcy; }

    public BigDecimal getUsdAmount() { return usdAmount; }
    public void setUsdAmount(BigDecimal usdAmount) { this.usdAmount = usdAmount; }

    public String getCommittedAt() { return committedAt; }
    public void setCommittedAt(String committedAt) { this.committedAt = committedAt; }

    public long getPartnerId() { return partnerId; }
    public void setPartnerId(long partnerId) { this.partnerId = partnerId; }
}
