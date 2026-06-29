package com.gme.pay.ledger.persistence;

import com.gme.pay.ledger.fees.CommissionSplit;
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
 * JPA entity mapping the {@code commission_splits} table (V005). Insert-only: one transaction's
 * two-sided commission split is written once (idempotent by {@code txnRef}) and never mutated.
 *
 * <p>Stores both the snapshot of the inputs the split was computed from (payout, rates, shares)
 * and the computed {@link CommissionSplit} (the 7 KRW amounts), so a row is independently auditable
 * and the split is reportable without re-running the calculator. Plain JavaBean — no Lombok.
 */
@Entity
@Table(name = "commission_splits")
public class CommissionSplitRecordEntity {

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

    // ---- input snapshot ----
    @Column(name = "payout_amount_krw", nullable = false)
    private long payoutAmountKrw;

    @Column(name = "merchant_fee_rate", nullable = false, precision = 7, scale = 4)
    private BigDecimal merchantFeeRate;

    @Column(name = "van_fee_rate", nullable = false, precision = 7, scale = 4)
    private BigDecimal vanFeeRate;

    @Column(name = "gme_share_pct", nullable = false, precision = 6, scale = 4)
    private BigDecimal gmeSharePct;

    @Column(name = "partner_share_pct", nullable = false, precision = 6, scale = 4)
    private BigDecimal partnerSharePct;

    // ---- computed split (CommissionSplit) ----
    @Column(name = "gross_merchant_fee_krw", nullable = false)
    private long grossMerchantFeeKrw;

    @Column(name = "van_fee_krw", nullable = false)
    private long vanFeeKrw;

    @Column(name = "net_merchant_fee_krw", nullable = false)
    private long netMerchantFeeKrw;

    @Column(name = "scheme_share_krw", nullable = false)
    private long schemeShareKrw;

    @Column(name = "gme_gross_share_krw", nullable = false)
    private long gmeGrossShareKrw;

    @Column(name = "partner_share_krw", nullable = false)
    private long partnerShareKrw;

    @Column(name = "gme_net_share_krw", nullable = false)
    private long gmeNetShareKrw;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CommissionSplitRecordEntity() {
        // JPA
    }

    /** Build a persistable entity from the inputs + the computed split. */
    public static CommissionSplitRecordEntity of(String txnRef, long partnerId, long schemeId,
                                                 LocalDate revenueDate, long payoutAmountKrw,
                                                 BigDecimal merchantFeeRate, BigDecimal vanFeeRate,
                                                 BigDecimal gmeSharePct, BigDecimal partnerSharePct,
                                                 CommissionSplit split, Instant createdAt) {
        CommissionSplitRecordEntity e = new CommissionSplitRecordEntity();
        e.txnRef = txnRef;
        e.partnerId = partnerId;
        e.schemeId = schemeId;
        e.revenueDate = revenueDate;
        e.payoutAmountKrw = payoutAmountKrw;
        e.merchantFeeRate = merchantFeeRate;
        e.vanFeeRate = vanFeeRate;
        e.gmeSharePct = gmeSharePct;
        e.partnerSharePct = partnerSharePct;
        e.grossMerchantFeeKrw = split.grossMerchantFeeKrw();
        e.vanFeeKrw = split.vanFeeKrw();
        e.netMerchantFeeKrw = split.netMerchantFeeKrw();
        e.schemeShareKrw = split.schemeShareKrw();
        e.gmeGrossShareKrw = split.gmeGrossShareKrw();
        e.partnerShareKrw = split.partnerShareKrw();
        e.gmeNetShareKrw = split.gmeNetShareKrw();
        e.createdAt = createdAt;
        return e;
    }

    public Long getId() {
        return id;
    }

    public String getTxnRef() {
        return txnRef;
    }

    public long getPartnerId() {
        return partnerId;
    }

    public long getSchemeId() {
        return schemeId;
    }

    public long getSchemeShareKrw() {
        return schemeShareKrw;
    }

    public long getGmeGrossShareKrw() {
        return gmeGrossShareKrw;
    }

    public long getPartnerShareKrw() {
        return partnerShareKrw;
    }

    public long getGmeNetShareKrw() {
        return gmeNetShareKrw;
    }

    public long getNetMerchantFeeKrw() {
        return netMerchantFeeKrw;
    }
}
