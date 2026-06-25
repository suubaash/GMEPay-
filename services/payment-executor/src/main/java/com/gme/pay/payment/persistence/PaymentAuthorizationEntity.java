package com.gme.pay.payment.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * State of a two-phase payment between {@code POST /v1/payments/authorize} and
 * {@code POST /v1/payments/{authId}/confirm} (SETTLEMENT_FLOW_SPEC §7.1).
 *
 * <p>At authorize we freeze the quote-derived amounts and reserve the partner float; the row is
 * keyed by a generated {@code authId} and is unique per (partner, partnerTxnRef). At confirm we
 * capture the hold and submit to the scheme. {@link #getStatus()} moves
 * AUTHORIZED → CONFIRMED / FAILED / RELEASED / EXPIRED.
 */
@Entity
@Table(name = "payment_authorizations")
public class PaymentAuthorizationEntity {

    public static final String STATUS_AUTHORIZED = "AUTHORIZED";
    /** Claimed for confirm — exactly one caller may hold this; gates out concurrent confirms. */
    public static final String STATUS_CONFIRMING = "CONFIRMING";
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_RELEASED = "RELEASED";
    public static final String STATUS_EXPIRED = "EXPIRED";
    /** Scheme outcome unknown after a timeout — NOT retryable via /confirm; awaits reconciliation. */
    public static final String STATUS_UNCERTAIN = "UNCERTAIN";

    @Id
    @Column(name = "auth_id", length = 40)
    private String authId;

    @Column(name = "partner_id", nullable = false)
    private long partnerId;

    @Column(name = "partner_code", length = 64)
    private String partnerCode;

    @Column(name = "partner_type", nullable = false, length = 16)
    private String partnerType;

    @Column(name = "partner_txn_ref", nullable = false, length = 128)
    private String partnerTxnRef;

    @Column(name = "quote_id", nullable = false, length = 64)
    private String quoteId;

    @Column(name = "scheme_id", nullable = false, length = 32)
    private String schemeId;

    @Column(name = "direction", length = 16)
    private String direction;

    @Column(name = "merchant_qr", length = 512)
    private String merchantQr;

    @Column(name = "customer_ref", length = 128)
    private String customerRef;

    @Column(name = "merchant_id", length = 64)
    private String merchantId;

    @Column(name = "merchant_name", length = 128)
    private String merchantName;

    @Column(name = "target_payout", nullable = false, precision = 20, scale = 8)
    private BigDecimal targetPayout;

    @Column(name = "payout_currency", nullable = false, length = 3)
    private String payoutCurrency;

    @Column(name = "collection_amount", nullable = false, precision = 20, scale = 8)
    private BigDecimal collectionAmount;

    @Column(name = "collection_currency", nullable = false, length = 3)
    private String collectionCurrency;

    @Column(name = "collection_usd", precision = 20, scale = 8)
    private BigDecimal collectionUsd;

    @Column(name = "collection_margin_usd", precision = 20, scale = 8)
    private BigDecimal collectionMarginUsd;

    @Column(name = "payout_margin_usd", precision = 20, scale = 8)
    private BigDecimal payoutMarginUsd;

    @Column(name = "service_charge", precision = 20, scale = 8)
    private BigDecimal serviceCharge;

    @Column(name = "merchant_fee_rate", precision = 7, scale = 4)
    private BigDecimal merchantFeeRate;

    @Column(name = "reserved_usd", precision = 20, scale = 8)
    private BigDecimal reservedUsd;

    @Column(name = "txn_ref", nullable = false, length = 64)
    private String txnRef;

    @Column(name = "payment_id", nullable = false, length = 64)
    private String paymentId;

    @Column(name = "status", nullable = false, length = 24)
    private String status;

    @Column(name = "wallet_charge_ref", length = 128)
    private String walletChargeRef;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    public PaymentAuthorizationEntity() {
    }

    public boolean isExpired(Instant now) {
        return now.isAfter(expiresAt);
    }

    // ---- getters ----
    public String getAuthId() { return authId; }
    public long getPartnerId() { return partnerId; }
    public String getPartnerCode() { return partnerCode; }
    public String getPartnerType() { return partnerType; }
    public String getPartnerTxnRef() { return partnerTxnRef; }
    public String getQuoteId() { return quoteId; }
    public String getSchemeId() { return schemeId; }
    public String getDirection() { return direction; }
    public String getMerchantQr() { return merchantQr; }
    public String getCustomerRef() { return customerRef; }
    public String getMerchantId() { return merchantId; }
    public String getMerchantName() { return merchantName; }
    public BigDecimal getTargetPayout() { return targetPayout; }
    public String getPayoutCurrency() { return payoutCurrency; }
    public BigDecimal getCollectionAmount() { return collectionAmount; }
    public String getCollectionCurrency() { return collectionCurrency; }
    public BigDecimal getCollectionUsd() { return collectionUsd; }
    public BigDecimal getCollectionMarginUsd() { return collectionMarginUsd; }
    public BigDecimal getPayoutMarginUsd() { return payoutMarginUsd; }
    public BigDecimal getServiceCharge() { return serviceCharge; }
    public BigDecimal getMerchantFeeRate() { return merchantFeeRate; }
    public BigDecimal getReservedUsd() { return reservedUsd; }
    public String getTxnRef() { return txnRef; }
    public String getPaymentId() { return paymentId; }
    public String getStatus() { return status; }
    public String getWalletChargeRef() { return walletChargeRef; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getConfirmedAt() { return confirmedAt; }

    // ---- setters ----
    public void setAuthId(String authId) { this.authId = authId; }
    public void setPartnerId(long partnerId) { this.partnerId = partnerId; }
    public void setPartnerCode(String partnerCode) { this.partnerCode = partnerCode; }
    public void setPartnerType(String partnerType) { this.partnerType = partnerType; }
    public void setPartnerTxnRef(String partnerTxnRef) { this.partnerTxnRef = partnerTxnRef; }
    public void setQuoteId(String quoteId) { this.quoteId = quoteId; }
    public void setSchemeId(String schemeId) { this.schemeId = schemeId; }
    public void setDirection(String direction) { this.direction = direction; }
    public void setMerchantQr(String merchantQr) { this.merchantQr = merchantQr; }
    public void setCustomerRef(String customerRef) { this.customerRef = customerRef; }
    public void setMerchantId(String merchantId) { this.merchantId = merchantId; }
    public void setMerchantName(String merchantName) { this.merchantName = merchantName; }
    public void setTargetPayout(BigDecimal targetPayout) { this.targetPayout = targetPayout; }
    public void setPayoutCurrency(String payoutCurrency) { this.payoutCurrency = payoutCurrency; }
    public void setCollectionAmount(BigDecimal collectionAmount) { this.collectionAmount = collectionAmount; }
    public void setCollectionCurrency(String collectionCurrency) { this.collectionCurrency = collectionCurrency; }
    public void setCollectionUsd(BigDecimal collectionUsd) { this.collectionUsd = collectionUsd; }
    public void setCollectionMarginUsd(BigDecimal v) { this.collectionMarginUsd = v; }
    public void setPayoutMarginUsd(BigDecimal v) { this.payoutMarginUsd = v; }
    public void setServiceCharge(BigDecimal v) { this.serviceCharge = v; }
    public void setMerchantFeeRate(BigDecimal merchantFeeRate) { this.merchantFeeRate = merchantFeeRate; }
    public void setReservedUsd(BigDecimal reservedUsd) { this.reservedUsd = reservedUsd; }
    public void setTxnRef(String txnRef) { this.txnRef = txnRef; }
    public void setPaymentId(String paymentId) { this.paymentId = paymentId; }
    public void setStatus(String status) { this.status = status; }
    public void setWalletChargeRef(String walletChargeRef) { this.walletChargeRef = walletChargeRef; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public void setConfirmedAt(Instant confirmedAt) { this.confirmedAt = confirmedAt; }
}
