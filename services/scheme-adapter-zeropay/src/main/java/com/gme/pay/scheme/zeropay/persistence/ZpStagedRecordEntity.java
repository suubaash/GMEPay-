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
 * One staged detail record from a ZeroPay batch file, linked to its registry row
 * in {@code zp_batch_files} via {@code batch_file_id}.
 *
 * <p>Maps {@code zp_staged_records} (V002 migration). Covers both directions:</p>
 * <ul>
 *   <li><b>ZP0011</b> (outbound payment result): payout/fee amounts, partner type,
 *       approval code, status code;</li>
 *   <li><b>ZP0012</b> (inbound registration result): result code and registered
 *       amount.</li>
 * </ul>
 *
 * <p>The reconciliation match key is {@code (zeropay_txn_ref, txn_date)} per
 * SCH-06 §5.3. All KRW amounts are {@link BigDecimal} with scale 0 (NUMERIC(20,0))
 * per {@code docs/MONEY_CONVENTION.md}.</p>
 */
@Entity
@Table(name = "zp_staged_records")
public class ZpStagedRecordEntity {

    public static final String RECORD_TYPE_ZP0011 = "ZP0011";
    public static final String RECORD_TYPE_ZP0012 = "ZP0012";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_file_id", nullable = false)
    private Long batchFileId;

    @Column(name = "record_type", nullable = false, length = 8)
    private String recordType;

    @Column(name = "line_number", nullable = false)
    private int lineNumber;

    @Column(name = "gme_txn_id", length = 20)
    private String gmeTxnId;

    @Column(name = "zeropay_txn_ref", nullable = false, length = 20)
    private String zeropayTxnRef;

    @Column(name = "merchant_id", length = 10)
    private String merchantId;

    @Column(name = "qr_code_id", length = 20)
    private String qrCodeId;

    @Column(name = "txn_date", nullable = false)
    private LocalDate txnDate;

    @Column(name = "txn_time")
    private LocalTime txnTime;

    @Column(name = "payout_amount_krw", precision = 20, scale = 0)
    private BigDecimal payoutAmountKrw;

    @Column(name = "merchant_fee_amt_krw", precision = 20, scale = 0)
    private BigDecimal merchantFeeAmtKrw;

    @Column(name = "van_fee_amt_krw", precision = 20, scale = 0)
    private BigDecimal vanFeeAmtKrw;

    @Column(name = "partner_type", length = 1)
    private String partnerType;

    @Column(name = "approval_code", length = 12)
    private String approvalCode;

    @Column(name = "status_code", length = 1)
    private String statusCode;

    @Column(name = "result_code", length = 4)
    private String resultCode;

    @Column(name = "registered_amount_krw", precision = 20, scale = 0)
    private BigDecimal registeredAmountKrw;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** JPA only. */
    protected ZpStagedRecordEntity() {
    }

    private ZpStagedRecordEntity(Long batchFileId, String recordType, int lineNumber,
                                 String zeropayTxnRef, LocalDate txnDate) {
        this.batchFileId = batchFileId;
        this.recordType = recordType;
        this.lineNumber = lineNumber;
        this.zeropayTxnRef = zeropayTxnRef;
        this.txnDate = txnDate;
    }

    /** Stages one ZP0011 (outbound payment-result) detail line. */
    public static ZpStagedRecordEntity zp0011Detail(Long batchFileId, int lineNumber,
                                                    String gmeTxnId, String zeropayTxnRef,
                                                    String merchantId, String qrCodeId,
                                                    LocalDate txnDate, LocalTime txnTime,
                                                    BigDecimal payoutAmountKrw,
                                                    BigDecimal merchantFeeAmtKrw,
                                                    BigDecimal vanFeeAmtKrw,
                                                    String partnerType, String approvalCode,
                                                    String statusCode) {
        ZpStagedRecordEntity entity = new ZpStagedRecordEntity(
                batchFileId, RECORD_TYPE_ZP0011, lineNumber, zeropayTxnRef, txnDate);
        entity.gmeTxnId = gmeTxnId;
        entity.merchantId = merchantId;
        entity.qrCodeId = qrCodeId;
        entity.txnTime = txnTime;
        entity.payoutAmountKrw = payoutAmountKrw;
        entity.merchantFeeAmtKrw = merchantFeeAmtKrw;
        entity.vanFeeAmtKrw = vanFeeAmtKrw;
        entity.partnerType = partnerType;
        entity.approvalCode = approvalCode;
        entity.statusCode = statusCode;
        return entity;
    }

    /** Stages one ZP0012 (inbound registration-result) detail line. */
    public static ZpStagedRecordEntity zp0012Result(Long batchFileId, int lineNumber,
                                                    String zeropayTxnRef, LocalDate txnDate,
                                                    String resultCode,
                                                    BigDecimal registeredAmountKrw) {
        ZpStagedRecordEntity entity = new ZpStagedRecordEntity(
                batchFileId, RECORD_TYPE_ZP0012, lineNumber, zeropayTxnRef, txnDate);
        entity.resultCode = resultCode;
        entity.registeredAmountKrw = registeredAmountKrw;
        return entity;
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

    public Long getBatchFileId() {
        return batchFileId;
    }

    public String getRecordType() {
        return recordType;
    }

    public int getLineNumber() {
        return lineNumber;
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

    public LocalDate getTxnDate() {
        return txnDate;
    }

    public LocalTime getTxnTime() {
        return txnTime;
    }

    public BigDecimal getPayoutAmountKrw() {
        return payoutAmountKrw;
    }

    public BigDecimal getMerchantFeeAmtKrw() {
        return merchantFeeAmtKrw;
    }

    public BigDecimal getVanFeeAmtKrw() {
        return vanFeeAmtKrw;
    }

    public String getPartnerType() {
        return partnerType;
    }

    public String getApprovalCode() {
        return approvalCode;
    }

    public String getStatusCode() {
        return statusCode;
    }

    public String getResultCode() {
        return resultCode;
    }

    public BigDecimal getRegisteredAmountKrw() {
        return registeredAmountKrw;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
