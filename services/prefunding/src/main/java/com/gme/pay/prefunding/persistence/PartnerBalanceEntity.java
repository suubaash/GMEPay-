package com.gme.pay.prefunding.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Persistent partner balance row. One row per partner. Money is stored as NUMERIC(20,8) and
 * mapped to {@link BigDecimal}. Mutations are serialized via SELECT FOR UPDATE in the repository.
 */
@Entity
@Table(name = "partner_balance")
public class PartnerBalanceEntity {

    @Id
    @Column(name = "partner_id", nullable = false, length = 32)
    private String partnerId;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "balance", nullable = false, precision = 20, scale = 8)
    private BigDecimal balance;

    /** Sum of active holds (RESERVE - CAPTURE - RELEASE). available = balance + creditLimit - reserved. */
    @Column(name = "reserved", nullable = false, precision = 20, scale = 8)
    private BigDecimal reserved = BigDecimal.ZERO;

    /** Per-partner credit headroom (V005; wired from config-registry in a later slice). */
    @Column(name = "credit_limit", nullable = false, precision = 20, scale = 8)
    private BigDecimal creditLimit = BigDecimal.ZERO;

    @Column(name = "low_balance_threshold", precision = 20, scale = 8)
    private BigDecimal lowBalanceThreshold;

    /** Per-partner AML caps pushed from config-registry (V007); NULL = no cap for that period. */
    @Column(name = "aml_daily_cap_usd", precision = 19, scale = 4)
    private BigDecimal amlDailyCapUsd;

    @Column(name = "aml_monthly_cap_usd", precision = 19, scale = 4)
    private BigDecimal amlMonthlyCapUsd;

    @Column(name = "aml_annual_cap_usd", precision = 19, scale = 4)
    private BigDecimal amlAnnualCapUsd;

    /** Per-partner daily transaction-count velocity cap (V007); NULL = no velocity cap. */
    @Column(name = "aml_daily_txn_count_cap")
    private Integer amlDailyTxnCountCap;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public PartnerBalanceEntity() {
        // JPA
    }

    public PartnerBalanceEntity(String partnerId, String currency, BigDecimal balance,
                                BigDecimal lowBalanceThreshold, Instant updatedAt) {
        this.partnerId = partnerId;
        this.currency = currency;
        this.balance = balance;
        this.lowBalanceThreshold = lowBalanceThreshold;
        this.updatedAt = updatedAt;
    }

    public String getPartnerId() {
        return partnerId;
    }

    public void setPartnerId(String partnerId) {
        this.partnerId = partnerId;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }

    public BigDecimal getReserved() {
        return reserved;
    }

    public void setReserved(BigDecimal reserved) {
        this.reserved = reserved;
    }

    public BigDecimal getCreditLimit() {
        return creditLimit;
    }

    public void setCreditLimit(BigDecimal creditLimit) {
        this.creditLimit = creditLimit;
    }

    public BigDecimal getLowBalanceThreshold() {
        return lowBalanceThreshold;
    }

    public void setLowBalanceThreshold(BigDecimal lowBalanceThreshold) {
        this.lowBalanceThreshold = lowBalanceThreshold;
    }

    public BigDecimal getAmlDailyCapUsd() {
        return amlDailyCapUsd;
    }

    public void setAmlDailyCapUsd(BigDecimal amlDailyCapUsd) {
        this.amlDailyCapUsd = amlDailyCapUsd;
    }

    public BigDecimal getAmlMonthlyCapUsd() {
        return amlMonthlyCapUsd;
    }

    public void setAmlMonthlyCapUsd(BigDecimal amlMonthlyCapUsd) {
        this.amlMonthlyCapUsd = amlMonthlyCapUsd;
    }

    public BigDecimal getAmlAnnualCapUsd() {
        return amlAnnualCapUsd;
    }

    public void setAmlAnnualCapUsd(BigDecimal amlAnnualCapUsd) {
        this.amlAnnualCapUsd = amlAnnualCapUsd;
    }

    public Integer getAmlDailyTxnCountCap() {
        return amlDailyTxnCountCap;
    }

    public void setAmlDailyTxnCountCap(Integer amlDailyTxnCountCap) {
        this.amlDailyTxnCountCap = amlDailyTxnCountCap;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
