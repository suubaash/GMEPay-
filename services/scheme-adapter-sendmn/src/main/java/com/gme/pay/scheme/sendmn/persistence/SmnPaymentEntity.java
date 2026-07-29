package com.gme.pay.scheme.sendmn.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One SendMN payment attempt, keyed by the partner-generated {@code TX_TOKEN_NO}
 * (unique — the scheme-level idempotency key reused across VerifyQr → Confirm →
 * PaymentStatus; SendMN rejects replays with error 304).
 */
@Entity
@Table(name = "smn_payments")
public class SmnPaymentEntity {

    /** Canonical lifecycle state (per ADR-016 anti-double-charge — never auto-fail). */
    public enum Status { VERIFIED, PENDING, APPROVED, REJECTED, UNKNOWN }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tx_token_no", nullable = false, unique = true, length = 64)
    private String txTokenNo;

    /**
     * The hub's (payment-executor's) stable partner reference, captured at verify-qr —
     * i.e. durably committed BEFORE any Confirm can be sent — so the ADR-016 status
     * probe survives a hub restart (V002). Not unique: a hub retry after a lost
     * verify-qr response may create a second attempt row for the same reference.
     */
    @Column(name = "hub_reference", length = 64)
    private String hubReference;

    @Column(name = "qr_code", length = 1024)
    private String qrCode;

    @Column(name = "merchant_id", length = 64)
    private String merchantId;

    @Column(name = "merchant_name", length = 128)
    private String merchantName;

    @Column(name = "local_cur_code", nullable = false, length = 3)
    private String localCurCode = "MNT";

    /** MNT amount, Decimal(18,2) per the SendMN contract. */
    @Column(name = "local_amount", precision = 18, scale = 2)
    private BigDecimal localAmount;

    /** Registered rate used at Confirm time (traceability toward error 307 / recon). */
    @Column(name = "fx_ticker_no", length = 64)
    private String fxTickerNo;

    @Column(name = "fx_usd_buy_rate", precision = 18, scale = 6)
    private BigDecimal fxUsdBuyRate;

    @Column(name = "settlement_cur_code", length = 3)
    private String settlementCurCode;

    /** USD amount, Decimal(18,4) = local / rate. */
    @Column(name = "settlement_amount", precision = 18, scale = 4)
    private BigDecimal settlementAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.VERIFIED;

    /** SendMN API tracking number (Confirm/PaymentStatus response). */
    @Column(name = "payment_no", length = 64)
    private String paymentNo;

    /** SendMN control number ("PAYMENT_RECIPT_NO" [sic] on the wire). */
    @Column(name = "payment_receipt_no", length = 64)
    private String paymentReceiptNo;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SmnPaymentEntity() {
        // JPA
    }

    public SmnPaymentEntity(String txTokenNo, String qrCode, String merchantId,
                            String merchantName, BigDecimal localAmount) {
        this.txTokenNo = txTokenNo;
        this.qrCode = qrCode;
        this.merchantId = merchantId;
        this.merchantName = merchantName;
        this.localAmount = localAmount;
    }

    @PrePersist
    void onPersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getTxTokenNo() { return txTokenNo; }
    public String getHubReference() { return hubReference; }
    public String getQrCode() { return qrCode; }
    public String getMerchantId() { return merchantId; }
    public String getMerchantName() { return merchantName; }
    public String getLocalCurCode() { return localCurCode; }
    public BigDecimal getLocalAmount() { return localAmount; }
    public String getFxTickerNo() { return fxTickerNo; }
    public BigDecimal getFxUsdBuyRate() { return fxUsdBuyRate; }
    public String getSettlementCurCode() { return settlementCurCode; }
    public BigDecimal getSettlementAmount() { return settlementAmount; }
    public Status getStatus() { return status; }
    public String getPaymentNo() { return paymentNo; }
    public String getPaymentReceiptNo() { return paymentReceiptNo; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setHubReference(String hubReference) { this.hubReference = hubReference; }
    public void setMerchantId(String merchantId) { this.merchantId = merchantId; }
    public void setMerchantName(String merchantName) { this.merchantName = merchantName; }
    public void setLocalAmount(BigDecimal localAmount) { this.localAmount = localAmount; }
    public void setStatus(Status status) { this.status = status; }
    public void setPaymentNo(String paymentNo) { this.paymentNo = paymentNo; }
    public void setPaymentReceiptNo(String paymentReceiptNo) { this.paymentReceiptNo = paymentReceiptNo; }

    public void recordFxUsed(String fxTickerNo, BigDecimal rate,
                             String settlementCurCode, BigDecimal settlementAmount) {
        this.fxTickerNo = fxTickerNo;
        this.fxUsdBuyRate = rate;
        this.settlementCurCode = settlementCurCode;
        this.settlementAmount = settlementAmount;
    }
}
