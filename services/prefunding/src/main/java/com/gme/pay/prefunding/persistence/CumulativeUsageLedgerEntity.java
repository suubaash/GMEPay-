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
 * Append-only row of the per-(partner, period) cumulative-usage ledger backing the AML daily/monthly/annual
 * caps (V006). A {@code CUM_CHARGE} carries a positive {@code amountUsd}; a {@code CUM_REVERSE} carries the
 * negation of its charge plus the charge's ORIGINAL period keys, so period usage = {@code SUM(amount_usd)}
 * over a {@code (partner_id, <period>_key)} nets correctly regardless of when the reverse is written.
 *
 * <p>Distinct from {@link LedgerEntryEntity} (the BALANCE ledger) on purpose: cumulative VOLUME is a
 * different concern from reserve/capture balance math, and keeping it separate avoids contaminating the
 * balance reconstruction.
 */
@Entity
@Table(name = "cumulative_usage_ledger")
public class CumulativeUsageLedgerEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "partner_id", nullable = false, length = 32)
    private String partnerId;

    @Column(name = "txn_ref", nullable = false, length = 64)
    private String txnRef;

    @Column(name = "entry_type", nullable = false, length = 16)
    private String entryType;

    @Column(name = "amount_usd", nullable = false, precision = 19, scale = 4)
    private BigDecimal amountUsd;

    @Column(name = "daily_key", nullable = false, length = 10)
    private String dailyKey;

    @Column(name = "monthly_key", nullable = false, length = 7)
    private String monthlyKey;

    @Column(name = "annual_key", nullable = false, length = 4)
    private String annualKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public CumulativeUsageLedgerEntity() {
        // JPA
    }

    public CumulativeUsageLedgerEntity(String partnerId, String txnRef, String entryType, BigDecimal amountUsd,
                                       String dailyKey, String monthlyKey, String annualKey, Instant createdAt) {
        this.partnerId = partnerId;
        this.txnRef = txnRef;
        this.entryType = entryType;
        this.amountUsd = amountUsd;
        this.dailyKey = dailyKey;
        this.monthlyKey = monthlyKey;
        this.annualKey = annualKey;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public String getPartnerId() { return partnerId; }
    public String getTxnRef() { return txnRef; }
    public String getEntryType() { return entryType; }
    public BigDecimal getAmountUsd() { return amountUsd; }
    public String getDailyKey() { return dailyKey; }
    public String getMonthlyKey() { return monthlyKey; }
    public String getAnnualKey() { return annualKey; }
    public Instant getCreatedAt() { return createdAt; }
}
