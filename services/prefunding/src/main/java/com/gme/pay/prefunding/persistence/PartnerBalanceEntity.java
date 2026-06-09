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

    @Column(name = "low_balance_threshold", precision = 20, scale = 8)
    private BigDecimal lowBalanceThreshold;

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

    public BigDecimal getLowBalanceThreshold() {
        return lowBalanceThreshold;
    }

    public void setLowBalanceThreshold(BigDecimal lowBalanceThreshold) {
        this.lowBalanceThreshold = lowBalanceThreshold;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
