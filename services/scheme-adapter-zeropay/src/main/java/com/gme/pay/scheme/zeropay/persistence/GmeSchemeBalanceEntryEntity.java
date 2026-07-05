package com.gme.pay.scheme.zeropay.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Append-only audit + idempotency journal for GME's ZeroPay prepaid float. One row per
 * OPENING / CREDIT / DEBIT applied to {@link GmeSchemeBalanceEntity}. Maps
 * {@code gme_scheme_balance_entry} (V004). The unique {@code (scheme_code, entry_type, txn_ref)}
 * constraint makes a retried payout or top-up a no-op (see {@code GmeSchemeFloatService}).
 */
@Entity
@Table(name = "gme_scheme_balance_entry")
public class GmeSchemeBalanceEntryEntity {

    public static final String TYPE_OPENING = "OPENING";
    public static final String TYPE_CREDIT  = "CREDIT";
    public static final String TYPE_DEBIT   = "DEBIT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "scheme_code", nullable = false, length = 16)
    private String schemeCode;

    @Column(name = "entry_type", nullable = false, length = 8)
    private String entryType;

    @Column(name = "txn_ref", nullable = false, length = 64)
    private String txnRef;

    @Column(name = "amount", nullable = false, precision = 20, scale = 0)
    private BigDecimal amount;

    @Column(name = "balance_after", nullable = false, precision = 20, scale = 0)
    private BigDecimal balanceAfter;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** JPA only. */
    protected GmeSchemeBalanceEntryEntity() {
    }

    public GmeSchemeBalanceEntryEntity(String schemeCode, String entryType, String txnRef,
                                       BigDecimal amount, BigDecimal balanceAfter) {
        this.schemeCode = schemeCode;
        this.entryType = entryType;
        this.txnRef = txnRef;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
    }

    @PrePersist
    void onPersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public String getSchemeCode() {
        return schemeCode;
    }

    public String getEntryType() {
        return entryType;
    }

    public String getTxnRef() {
        return txnRef;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getBalanceAfter() {
        return balanceAfter;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
