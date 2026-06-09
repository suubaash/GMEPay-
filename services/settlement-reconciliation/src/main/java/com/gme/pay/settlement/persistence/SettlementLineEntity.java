package com.gme.pay.settlement.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * JPA entity mapping {@code settlement_lines}.
 *
 * <p>One row per transaction included in a settlement batch. The {@code matched} flag
 * is updated when the corresponding scheme-confirmed amount is reconciled.
 */
@Entity
@Table(name = "settlement_lines")
public class SettlementLineEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "batch_id", length = 64, nullable = false)
    private String batchId;

    @Column(name = "txn_ref", length = 64)
    private String txnRef;

    @Column(name = "amount", precision = 20, scale = 8, nullable = false)
    private BigDecimal amount;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Column(name = "matched", nullable = false)
    private boolean matched;

    public SettlementLineEntity() {
        // JPA no-arg constructor
    }

    public SettlementLineEntity(String batchId,
                                String txnRef,
                                BigDecimal amount,
                                String currency,
                                boolean matched) {
        this.batchId = batchId;
        this.txnRef = txnRef;
        this.amount = amount;
        this.currency = currency;
        this.matched = matched;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getBatchId() {
        return batchId;
    }

    public void setBatchId(String batchId) {
        this.batchId = batchId;
    }

    public String getTxnRef() {
        return txnRef;
    }

    public void setTxnRef(String txnRef) {
        this.txnRef = txnRef;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public boolean isMatched() {
        return matched;
    }

    public void setMatched(boolean matched) {
        this.matched = matched;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SettlementLineEntity that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
