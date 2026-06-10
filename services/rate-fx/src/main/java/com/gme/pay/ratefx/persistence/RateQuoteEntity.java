package com.gme.pay.ratefx.persistence;

import com.gme.pay.ratefx.quote.StoredQuote;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Durable audit copy of an issued rate quote (RATE-04 §9). The TTL / rate-lock
 * semantics live in the {@code QuoteTtlStore} (Redis key {@code rq:{quoteId}});
 * this row never expires.
 *
 * <p>All money/rate fields are {@link BigDecimal} mapped to NUMERIC(20,8) per
 * docs/MONEY_CONVENTION.md — never double/float. Schema owned by Flyway
 * ({@code V002__create_rate_quotes.sql}).
 */
@Entity
@Table(name = "rate_quotes")
public class RateQuoteEntity {

    @Id
    @Column(name = "quote_id", length = 64, nullable = false)
    private String quoteId;

    @Column(name = "collection_ccy", length = 3, nullable = false)
    private String collectionCcy;

    @Column(name = "settle_a_ccy", length = 3, nullable = false)
    private String settleACcy;

    @Column(name = "settle_b_ccy", length = 3, nullable = false)
    private String settleBCcy;

    @Column(name = "payout_ccy", length = 3, nullable = false)
    private String payoutCcy;

    @Column(name = "target_payout", precision = 20, scale = 8, nullable = false)
    private BigDecimal targetPayout;

    @Column(name = "payout_usd_cost", precision = 20, scale = 8, nullable = false)
    private BigDecimal payoutUsdCost;

    @Column(name = "collection_usd", precision = 20, scale = 8, nullable = false)
    private BigDecimal collectionUsd;

    @Column(name = "collection_margin_usd", precision = 20, scale = 8, nullable = false)
    private BigDecimal collectionMarginUsd;

    @Column(name = "payout_margin_usd", precision = 20, scale = 8, nullable = false)
    private BigDecimal payoutMarginUsd;

    @Column(name = "send_amount", precision = 20, scale = 8, nullable = false)
    private BigDecimal sendAmount;

    @Column(name = "collection_amount", precision = 20, scale = 8, nullable = false)
    private BigDecimal collectionAmount;

    /** BOK FX1015 field #14 — nullable (NULL allowed for same-currency flows). */
    @Column(name = "offer_rate_coll", precision = 20, scale = 8)
    private BigDecimal offerRateColl;

    /** target_payout / send_amount reference rate — nullable for same-currency flows. */
    @Column(name = "cross_rate", precision = 20, scale = 8)
    private BigDecimal crossRate;

    @Column(name = "short_circuit", nullable = false)
    private boolean shortCircuit;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** JPA only. */
    protected RateQuoteEntity() {
    }

    public RateQuoteEntity(String quoteId, String collectionCcy, String settleACcy, String settleBCcy,
                           String payoutCcy, BigDecimal targetPayout, BigDecimal payoutUsdCost,
                           BigDecimal collectionUsd, BigDecimal collectionMarginUsd, BigDecimal payoutMarginUsd,
                           BigDecimal sendAmount, BigDecimal collectionAmount, BigDecimal offerRateColl,
                           BigDecimal crossRate, boolean shortCircuit, Instant createdAt, Instant expiresAt) {
        this.quoteId = quoteId;
        this.collectionCcy = collectionCcy;
        this.settleACcy = settleACcy;
        this.settleBCcy = settleBCcy;
        this.payoutCcy = payoutCcy;
        this.targetPayout = targetPayout;
        this.payoutUsdCost = payoutUsdCost;
        this.collectionUsd = collectionUsd;
        this.collectionMarginUsd = collectionMarginUsd;
        this.payoutMarginUsd = payoutMarginUsd;
        this.sendAmount = sendAmount;
        this.collectionAmount = collectionAmount;
        this.offerRateColl = offerRateColl;
        this.crossRate = crossRate;
        this.shortCircuit = shortCircuit;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    /** Maps the TTL-store value object onto the durable audit row. */
    public static RateQuoteEntity fromStored(StoredQuote quote) {
        return new RateQuoteEntity(
                quote.quoteId(),
                quote.collectionCurrency(), quote.settleACurrency(),
                quote.settleBCurrency(), quote.payoutCurrency(),
                quote.targetPayout(), quote.payoutUsdCost(), quote.collectionUsd(),
                quote.collectionMarginUsd(), quote.payoutMarginUsd(),
                quote.sendAmount(), quote.collectionAmount(),
                quote.offerRateColl(), quote.crossRate(),
                quote.shortCircuit(), quote.createdAt(), quote.expiresAt());
    }

    /** Rehydrates the value object from the audit row. */
    public StoredQuote toStored() {
        return new StoredQuote(
                quoteId, collectionCcy, settleACcy, settleBCcy, payoutCcy,
                targetPayout, payoutUsdCost, collectionUsd, collectionMarginUsd, payoutMarginUsd,
                sendAmount, collectionAmount, offerRateColl, crossRate,
                shortCircuit, createdAt, expiresAt);
    }

    public String getQuoteId() {
        return quoteId;
    }

    public String getCollectionCcy() {
        return collectionCcy;
    }

    public String getSettleACcy() {
        return settleACcy;
    }

    public String getSettleBCcy() {
        return settleBCcy;
    }

    public String getPayoutCcy() {
        return payoutCcy;
    }

    public BigDecimal getTargetPayout() {
        return targetPayout;
    }

    public BigDecimal getPayoutUsdCost() {
        return payoutUsdCost;
    }

    public BigDecimal getCollectionUsd() {
        return collectionUsd;
    }

    public BigDecimal getCollectionMarginUsd() {
        return collectionMarginUsd;
    }

    public BigDecimal getPayoutMarginUsd() {
        return payoutMarginUsd;
    }

    public BigDecimal getSendAmount() {
        return sendAmount;
    }

    public BigDecimal getCollectionAmount() {
        return collectionAmount;
    }

    public BigDecimal getOfferRateColl() {
        return offerRateColl;
    }

    public BigDecimal getCrossRate() {
        return crossRate;
    }

    public boolean isShortCircuit() {
        return shortCircuit;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
