package com.gme.pay.reporting.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * API response DTO for a single BOK FX record (FX1014 or FX1015).
 *
 * <p>All BigDecimal fields are serialised as plain decimal strings (no scientific notation)
 * — configure ObjectMapper with {@code WRITE_BIGDECIMAL_AS_PLAIN} in production.
 */
public class BokFxRecordDto {

    @JsonProperty("txn_id")
    private long txnId;

    @JsonProperty("txn_ref")
    private String txnRef;

    @JsonProperty("report_type")
    private String reportType;

    @JsonProperty("report_date")
    private LocalDate reportDate;

    @JsonProperty("partner_id")
    private long partnerId;

    @JsonProperty("collection_amount")
    private BigDecimal collectionAmount;

    @JsonProperty("collection_ccy")
    private String collectionCcy;

    @JsonProperty("payout_amount")
    private BigDecimal payoutAmount;

    @JsonProperty("payout_ccy")
    private String payoutCcy;

    /**
     * BOK FX1015 field #14.
     * Formula: send_amount / (collection_usd - collection_margin_usd).
     * Scale 8, HALF_UP.
     */
    @JsonProperty("offer_rate_coll")
    private BigDecimal offerRateColl;

    @JsonProperty("cross_rate")
    private BigDecimal crossRate;

    @JsonProperty("usd_amount")
    private BigDecimal usdAmount;

    @JsonProperty("submission_status")
    private String submissionStatus;

    public BokFxRecordDto() {}

    public long getTxnId() { return txnId; }
    public void setTxnId(long txnId) { this.txnId = txnId; }

    public String getTxnRef() { return txnRef; }
    public void setTxnRef(String txnRef) { this.txnRef = txnRef; }

    public String getReportType() { return reportType; }
    public void setReportType(String reportType) { this.reportType = reportType; }

    public LocalDate getReportDate() { return reportDate; }
    public void setReportDate(LocalDate reportDate) { this.reportDate = reportDate; }

    public long getPartnerId() { return partnerId; }
    public void setPartnerId(long partnerId) { this.partnerId = partnerId; }

    public BigDecimal getCollectionAmount() { return collectionAmount; }
    public void setCollectionAmount(BigDecimal collectionAmount) { this.collectionAmount = collectionAmount; }

    public String getCollectionCcy() { return collectionCcy; }
    public void setCollectionCcy(String collectionCcy) { this.collectionCcy = collectionCcy; }

    public BigDecimal getPayoutAmount() { return payoutAmount; }
    public void setPayoutAmount(BigDecimal payoutAmount) { this.payoutAmount = payoutAmount; }

    public String getPayoutCcy() { return payoutCcy; }
    public void setPayoutCcy(String payoutCcy) { this.payoutCcy = payoutCcy; }

    public BigDecimal getOfferRateColl() { return offerRateColl; }
    public void setOfferRateColl(BigDecimal offerRateColl) { this.offerRateColl = offerRateColl; }

    public BigDecimal getCrossRate() { return crossRate; }
    public void setCrossRate(BigDecimal crossRate) { this.crossRate = crossRate; }

    public BigDecimal getUsdAmount() { return usdAmount; }
    public void setUsdAmount(BigDecimal usdAmount) { this.usdAmount = usdAmount; }

    public String getSubmissionStatus() { return submissionStatus; }
    public void setSubmissionStatus(String submissionStatus) { this.submissionStatus = submissionStatus; }
}
