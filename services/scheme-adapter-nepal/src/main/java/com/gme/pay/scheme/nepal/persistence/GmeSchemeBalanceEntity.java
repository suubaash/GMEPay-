package com.gme.pay.scheme.nepal.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * GME's running prepaid float held WITH the Nepal QR scheme (one row, keyed by scheme code).
 * Maps {@code gme_scheme_balance} (V001). The pre-submit balance-check reads {@link #getBalance()};
 * every committed payout debits it. NPR carries paisa, so the balance is scale 2 (NUMERIC(20,2)).
 */
@Entity
@Table(name = "gme_scheme_balance")
public class GmeSchemeBalanceEntity {

    @Id
    @Column(name = "scheme_code", nullable = false, length = 16)
    private String schemeCode;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "balance", nullable = false, precision = 20, scale = 2)
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
