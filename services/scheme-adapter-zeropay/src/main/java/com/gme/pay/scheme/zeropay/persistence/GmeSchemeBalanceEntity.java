package com.gme.pay.scheme.zeropay.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * GME's running prepaid float held WITH the ZeroPay scheme (one row, keyed by scheme code).
 * Maps {@code gme_scheme_balance} (V004). The pre-submit balance-check reads {@link #getBalance()};
 * every committed payout debits it. KRW is {@link BigDecimal} scale 0 per docs/MONEY_CONVENTION.md.
 */
@Entity
@Table(name = "gme_scheme_balance")
public class GmeSchemeBalanceEntity {

    @Id
    @Column(name = "scheme_code", nullable = false, length = 16)
    private String schemeCode;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "balance", nullable = false, precision = 20, scale = 0)
    private BigDecimal balance;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** JPA only. */
    protected GmeSchemeBalanceEntity() {
    }

    public GmeSchemeBalanceEntity(String schemeCode, String currency, BigDecimal balance) {
        this.schemeCode = schemeCode;
        this.currency = currency;
        this.balance = balance;
    }

    @PrePersist
    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public String getSchemeCode() {
        return schemeCode;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
