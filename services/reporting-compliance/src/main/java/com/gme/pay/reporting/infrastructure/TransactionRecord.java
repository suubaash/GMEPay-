package com.gme.pay.reporting.infrastructure;

/**
 * Wire DTO for a single transaction item from {@code GET /v1/transactions} on the
 * transaction-mgmt service.
 *
 * <p>Field names are camelCase, matching transaction-mgmt's {@code TransactionResponse}
 * wire shape exactly (Jackson binds by field name; any mismatch silently produces null).
 *
 * <p>Money fields are received as JSON strings (BigDecimal-as-string contract).
 *
 * <p>Canonical shape (NON_NULL fields only):
 * <pre>
 * {
 *   "txnRef":                String,
 *   "partnerRef":            String,
 *   "sendAmount":            String (BigDecimal-as-string),
 *   "sendCcy":               String,
 *   "targetPayout":          String (BigDecimal-as-string),
 *   "targetCcy":             String,
 *   "status":                String (TransactionStatus name),
 *   "createdAt":             String (ISO-8601 instant),
 *   "updatedAt":             String (ISO-8601 instant),
 *   "qrSchemeId":            String | null,
 *   "krwAmount":             String | null,
 *   "payerCurrency":         String | null,
 *   "payerCurrencyAmount":   String | null,
 *   "appliedFxRate":         String | null,
 *   "rateTimestamp":         String | null,
 *   "prefundingDeductedUsd": String | null,
 *   "statusHistory":         [...] | null,
 *   "merchantId":            String | null,
 *   "merchantName":          String | null
 * }
 * </pre>
 */
public class TransactionRecord {

    /** transaction-mgmt internal UUID reference. */
    private String txnRef;

    /** Partner reference (= partnerTxnRef for V003 transactions). */
    private String partnerRef;

    /** Amount sent by payer (= collectionAmount). BigDecimal-as-string. */
    private String sendAmount;

    /** Payer currency ISO-4217 (= collectionCurrency). */
    private String sendCcy;

    /** Payout amount in targetCcy. BigDecimal-as-string. */
    private String targetPayout;

    /** Payout currency ISO-4217. */
    private String targetCcy;

    /** Current TransactionStatus name (e.g. APPROVED, CREATED). */
    private String status;

    /** UTC creation instant. ISO-8601 string. */
    private String createdAt;

    /** UTC last-update instant. ISO-8601 string. */
    private String updatedAt;

    /** QR scheme identifier, e.g. "zeropay_kr". Nullable. */
    private String qrSchemeId;

    /**
     * KRW payout amount (= targetPayout when targetCcy=="KRW"). BigDecimal-as-string. Nullable.
     * <p>BOK use: KRW leg amount for inbound transactions.
     */
    private String krwAmount;

    /** ISO-4217 payer currency (= sendCcy). Nullable. */
    private String payerCurrency;

    /** Amount in payer's currency (= sendAmount). BigDecimal-as-string. Nullable. */
    private String payerCurrencyAmount;

    /**
     * FX rate applied at commit: targetPayout / sendAmount. BigDecimal-as-string. Nullable.
     * <p>BOK use: this is the cross-rate (crossRate = target_payout / send_amount).
     * <p>NULL for same-currency transactions where sendCcy == targetCcy.
     */
    private String appliedFxRate;

    /** UTC instant FX rate was locked. ISO-8601 string. Nullable. */
    private String rateTimestamp;

    /**
     * USD deducted from partner's prefunding balance. BigDecimal-as-string. Nullable.
     * <p>BOK use: usdAmount equivalent (payout_usd_cost).
     */
    private String prefundingDeductedUsd;

    /** Merchant terminal/store id from the QR scheme. Nullable. */
    private String merchantId;

    /** Merchant display name. Nullable. */
    private String merchantName;

    public TransactionRecord() {}

    public String getTxnRef() { return txnRef; }
    public void setTxnRef(String txnRef) { this.txnRef = txnRef; }

    public String getPartnerRef() { return partnerRef; }
    public void setPartnerRef(String partnerRef) { this.partnerRef = partnerRef; }

    public String getSendAmount() { return sendAmount; }
    public void setSendAmount(String sendAmount) { this.sendAmount = sendAmount; }

    public String getSendCcy() { return sendCcy; }
    public void setSendCcy(String sendCcy) { this.sendCcy = sendCcy; }

    public String getTargetPayout() { return targetPayout; }
    public void setTargetPayout(String targetPayout) { this.targetPayout = targetPayout; }

    public String getTargetCcy() { return targetCcy; }
    public void setTargetCcy(String targetCcy) { this.targetCcy = targetCcy; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }

    public String getQrSchemeId() { return qrSchemeId; }
    public void setQrSchemeId(String qrSchemeId) { this.qrSchemeId = qrSchemeId; }

    public String getKrwAmount() { return krwAmount; }
    public void setKrwAmount(String krwAmount) { this.krwAmount = krwAmount; }

    public String getPayerCurrency() { return payerCurrency; }
    public void setPayerCurrency(String payerCurrency) { this.payerCurrency = payerCurrency; }

    public String getPayerCurrencyAmount() { return payerCurrencyAmount; }
    public void setPayerCurrencyAmount(String payerCurrencyAmount) { this.payerCurrencyAmount = payerCurrencyAmount; }

    public String getAppliedFxRate() { return appliedFxRate; }
    public void setAppliedFxRate(String appliedFxRate) { this.appliedFxRate = appliedFxRate; }

    public String getRateTimestamp() { return rateTimestamp; }
    public void setRateTimestamp(String rateTimestamp) { this.rateTimestamp = rateTimestamp; }

    public String getPrefundingDeductedUsd() { return prefundingDeductedUsd; }
    public void setPrefundingDeductedUsd(String prefundingDeductedUsd) { this.prefundingDeductedUsd = prefundingDeductedUsd; }

    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String merchantId) { this.merchantId = merchantId; }

    public String getMerchantName() { return merchantName; }
    public void setMerchantName(String merchantName) { this.merchantName = merchantName; }
}
