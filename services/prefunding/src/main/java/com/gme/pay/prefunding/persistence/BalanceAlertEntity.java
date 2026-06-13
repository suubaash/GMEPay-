package com.gme.pay.prefunding.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One raised low-balance alert (Slice 5 — Prefunding). Mapped 1:1 onto
 * {@code V003__create_balance_alert.sql}. Rows are append-only except for the
 * {@code acknowledged} flag, which an operator flips to re-arm the tier
 * (hysteresis — see {@link com.gme.pay.prefunding.alert.TierAlertEvaluator}).
 *
 * <p>{@code tier} is one of {@code TIER_95}, {@code TIER_85}, {@code TIER_70}
 * (balance crossed DOWN through that % of the low-balance threshold) or
 * {@code BREACH} (balance went negative) — enforced by the table CHECK.
 */
@Entity
@Table(name = "balance_alert")
public class BalanceAlertEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "partner_code", nullable = false, length = 20)
    private String partnerCode;

    @Column(name = "tier", nullable = false, length = 10)
    private String tier;

    @Column(name = "balance_usd", nullable = false, precision = 19, scale = 4)
    private BigDecimal balanceUsd;

    @Column(name = "threshold_usd", nullable = false, precision = 19, scale = 4)
    private BigDecimal thresholdUsd;

    @Column(name = "raised_at", nullable = false)
    private Instant raisedAt;

    @Column(name = "acknowledged", nullable = false)
    private boolean acknowledged;

    public BalanceAlertEntity() {
        // JPA
    }

    public BalanceAlertEntity(String partnerCode, String tier, BigDecimal balanceUsd,
                              BigDecimal thresholdUsd, Instant raisedAt) {
        this.partnerCode = partnerCode;
        this.tier = tier;
        this.balanceUsd = balanceUsd;
        this.thresholdUsd = thresholdUsd;
        this.raisedAt = raisedAt;
        this.acknowledged = false;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPartnerCode() {
        return partnerCode;
    }

    public void setPartnerCode(String partnerCode) {
        this.partnerCode = partnerCode;
    }

    public String getTier() {
        return tier;
    }

    public void setTier(String tier) {
        this.tier = tier;
    }

    public BigDecimal getBalanceUsd() {
        return balanceUsd;
    }

    public void setBalanceUsd(BigDecimal balanceUsd) {
        this.balanceUsd = balanceUsd;
    }

    public BigDecimal getThresholdUsd() {
        return thresholdUsd;
    }

    public void setThresholdUsd(BigDecimal thresholdUsd) {
        this.thresholdUsd = thresholdUsd;
    }

    public Instant getRaisedAt() {
        return raisedAt;
    }

    public void setRaisedAt(Instant raisedAt) {
        this.raisedAt = raisedAt;
    }

    public boolean isAcknowledged() {
        return acknowledged;
    }

    public void setAcknowledged(boolean acknowledged) {
        this.acknowledged = acknowledged;
    }
}
