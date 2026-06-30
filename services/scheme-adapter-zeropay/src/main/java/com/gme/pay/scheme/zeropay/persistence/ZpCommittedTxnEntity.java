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
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * One real-time transaction captured at commit time — either a committed MPM/CPM payment
 * or a completed refund. Maps {@code zp_committed_txns} (V003 migration).
 *
 * <p>This is the local, in-service source of truth that {@code ZpPersistenceBatchDataPort}
 * reads to build the daily ZeroPay ZP00xx outbound files with non-empty records, replacing
 * the zero-record {@code ZpStubBatchDataPort}. It is populated by {@code ZpCommittedTxnRecorder}
 * on the real-time payment path; it does NOT depend on transaction-management or settlement-mgmt
 * (those services are frozen and would require a published cross-service contract — see the
 * INTEGRATION REQUESTS in the build report).</p>
 *
 * <p>All KRW amounts are {@link BigDecimal} scale 0 (NUMERIC(20,0)) per
 * {@code docs/MONEY_CONVENTION.md}.</p>
 */
@Entity
@Table(name = "zp_committed_txns")
public class ZpCommittedTxnEntity {

    public static final String KIND_PAYMENT = "PAYMENT";
    public static final String KIND_REFUND  = "REFUND";

    public static final String STATUS_APPROVED = "A";
    public static final String STATUS_REFUNDED = "R";

    public static final String PARTNER_DOMESTIC      = "D";
    public static final String PARTNER_INTERNATIONAL = "I";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "txn_kind", nullable = false, length = 8)
    private String txnKind;

    @Column(name = "gme_txn_id", length = 20)
    private String gmeTxnId;

    @Column(name = "zeropay_txn_ref", nullable = false, length = 20)
    private String zeropayTxnRef;

    @Column(name = "merchant_id", length = 10)
    private String merchantId;

    @Column(name = "qr_code_id", length = 20)
    private String qrCodeId;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "txn_time", nullable = false)
    private LocalTime txnTime;

    @Column(name = "amount_krw", nullable = false, precision = 20, scale = 0)
    private BigDecimal amountKrw;

    @Column(name = "merchant_fee_krw", nullable = false, precision = 20, scale = 0)
    private BigDecimal merchantFeeKrw;

    @Column(name = "van_fee_krw", nullable = false, precision = 20, scale = 0)
    private BigDecimal vanFeeKrw;

    @Column(name = "partner_type", nullable = false, length = 1)
    private String partnerType;

    @Column(name = "approval_code", nullable = false, length = 12)
    private String approvalCode;

    @Column(name = "original_approval_code", length = 12)
    private String originalApprovalCode;

    @Column(name = "settlement_date")
    private LocalDate settlementDate;

    @Column(name = "status_code", nullable = false, length = 1)
    private String statusCode;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** JPA only. */
    protected ZpCommittedTxnEntity() {
    }

    private ZpCommittedTxnEntity(String txnKind, String zeropayTxnRef, LocalDate businessDate,
                                 LocalTime txnTime, BigDecimal amountKrw, String approvalCode,
                                 String statusCode) {
        this.txnKind = txnKind;
        this.zeropayTxnRef = zeropayTxnRef;
        this.businessDate = businessDate;
        this.txnTime = txnTime;
        this.amountKrw = amountKrw;
        this.approvalCode = approvalCode;
        this.statusCode = statusCode;
        this.merchantFeeKrw = BigDecimal.ZERO;
        this.vanFeeKrw = BigDecimal.ZERO;
        this.partnerType = PARTNER_DOMESTIC;
    }

    /** Captures a committed MPM/CPM payment. */
    public static ZpCommittedTxnEntity payment(String gmeTxnId, String zeropayTxnRef,
                                               String merchantId, String qrCodeId,
                                               LocalDate businessDate, LocalTime txnTime,
                                               BigDecimal amountKrw, BigDecimal merchantFeeKrw,
                                               BigDecimal vanFeeKrw, String partnerType,
                                               String approvalCode, LocalDate settlementDate) {
        ZpCommittedTxnEntity e = new ZpCommittedTxnEntity(
                KIND_PAYMENT, zeropayTxnRef, businessDate, txnTime, amountKrw, approvalCode,
                STATUS_APPROVED);
        e.gmeTxnId = gmeTxnId;
        e.merchantId = merchantId;
        e.qrCodeId = qrCodeId;
        e.merchantFeeKrw = nz(merchantFeeKrw);
        e.vanFeeKrw = nz(vanFeeKrw);
        e.partnerType = partnerType == null ? PARTNER_DOMESTIC : partnerType;
        e.settlementDate = settlementDate;
        return e;
    }

    /** Captures a completed refund of a previously committed payment. */
    public static ZpCommittedTxnEntity refund(String gmeTxnId, String zeropayTxnRef,
                                              String merchantId, String qrCodeId,
                                              LocalDate businessDate, LocalTime txnTime,
                                              BigDecimal amountKrw, BigDecimal merchantFeeKrw,
                                              BigDecimal vanFeeKrw, String partnerType,
                                              String refundId, String originalApprovalCode,
                                              LocalDate settlementDate) {
        ZpCommittedTxnEntity e = new ZpCommittedTxnEntity(
                KIND_REFUND, zeropayTxnRef, businessDate, txnTime, amountKrw, refundId,
                STATUS_REFUNDED);
        e.gmeTxnId = gmeTxnId;
        e.merchantId = merchantId;
        e.qrCodeId = qrCodeId;
        e.merchantFeeKrw = nz(merchantFeeKrw);
        e.vanFeeKrw = nz(vanFeeKrw);
        e.partnerType = partnerType == null ? PARTNER_DOMESTIC : partnerType;
        e.originalApprovalCode = originalApprovalCode;
        e.settlementDate = settlementDate;
        return e;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    @PrePersist
    void onPersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    // -- accessors ------------------------------------------------------------

    public Long getId() {
        return id;
    }

    public String getTxnKind() {
        return txnKind;
    }

    public String getGmeTxnId() {
        return gmeTxnId;
    }

    public String getZeropayTxnRef() {
        return zeropayTxnRef;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public String getQrCodeId() {
        return qrCodeId;
    }

    public LocalDate getBusinessDate() {
        return businessDate;
    }

    public LocalTime getTxnTime() {
        return txnTime;
    }

    public BigDecimal getAmountKrw() {
        return amountKrw;
    }

    public BigDecimal getMerchantFeeKrw() {
        return merchantFeeKrw;
    }

    public BigDecimal getVanFeeKrw() {
        return vanFeeKrw;
    }

    public String getPartnerType() {
        return partnerType;
    }

    public String getApprovalCode() {
        return approvalCode;
    }

    public String getOriginalApprovalCode() {
        return originalApprovalCode;
    }

    public LocalDate getSettlementDate() {
        return settlementDate;
    }

    public String getStatusCode() {
        return statusCode;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
