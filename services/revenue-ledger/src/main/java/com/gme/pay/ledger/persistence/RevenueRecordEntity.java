package com.gme.pay.ledger.persistence;

import com.gme.pay.ledger.revenue.RevenueRecord;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * JPA entity mapping the {@code revenue_records} table (V004). Insert-only: a committed
 * transaction's revenue is written once (idempotent by {@code txnRef}) and never mutated.
 *
 * <p>Plain JavaBean — no Lombok — per project conventions.
 */
@Entity
@Table(name = "revenue_records")
public class RevenueRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "txn_ref", nullable = false, unique = true, length = 64)
    private String txnRef;

    @Column(name = "partner_id", nullable = false)
    private long partnerId;

    @Column(name = "scheme_id", nullable = false)
    private long schemeId;

    @Column(name = "revenue_date", nullable = false)
    private LocalDate revenueDate;

    @Column(name = "fx_margin_usd", nullable = false, precision = 20, scale = 4)
    private BigDecimal fxMarginUsd;

    @Column(name = "service_charge_amount", nullable = false, precision = 20, scale = 4)
    private BigDecimal serviceChargeAmount;

    @Column(name = "service_charge_ccy", nullable = false, length = 3)
    private String serviceChargeCcy;

    @Column(name = "fee_share_pct", nullable = false, precision = 6, scale = 4)
    private BigDecimal feeSharePct;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RevenueRecordEntity() {
        // JPA
    }

    /** Build a persistable entity from a domain {@link RevenueRecord}. */
    public static RevenueRecordEntity fromDomain(RevenueRecord r, Instant createdAt) {
        RevenueRecordEntity e = new RevenueRecordEntity();
        e.txnRef = r.txnRef();
        e.partnerId = r.partnerId();
        e.schemeId = r.schemeId();
        e.revenueDate = r.revenueDate();
        e.fxMarginUsd = r.fxMarginUsd();
        e.serviceChargeAmount = r.serviceChargeAmount();
        e.serviceChargeCcy = r.serviceChargeCcy();
        e.feeSharePct = r.feeSharePct();
        e.createdAt = createdAt;
        return e;
    }

    /** Rebuild the domain value from this persisted row (stored fxMarginUsd preserved as-is). */
    public RevenueRecord toDomain() {
        return RevenueRecord.rehydrate(txnRef, partnerId, schemeId, revenueDate,
                fxMarginUsd, serviceChargeAmount, serviceChargeCcy, feeSharePct);
    }

    public Long getId() { return id; }
    public String getTxnRef() { return txnRef; }
    public long getPartnerId() { return partnerId; }
    public long getSchemeId() { return schemeId; }
    public LocalDate getRevenueDate() { return revenueDate; }
    public BigDecimal getFxMarginUsd() { return fxMarginUsd; }
    public BigDecimal getServiceChargeAmount() { return serviceChargeAmount; }
    public String getServiceChargeCcy() { return serviceChargeCcy; }
    public BigDecimal getFeeSharePct() { return feeSharePct; }
    public Instant getCreatedAt() { return createdAt; }
}
