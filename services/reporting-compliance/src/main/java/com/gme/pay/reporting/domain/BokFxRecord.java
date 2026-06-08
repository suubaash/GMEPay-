package com.gme.pay.reporting.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A single BOK FX1014 or FX1015 record derived from one committed transaction.
 *
 * <p>Field #14 of the FX1015 form is {@code offerRateColl} (offer_rate_coll):
 * <pre>
 *   offerRateColl = send_amount / (collection_usd - collection_margin_usd)
 * </pre>
 * This value is locked at CommitTransaction time and copied here verbatim.
 *
 * <p>Domestic (same-currency) transactions MUST NOT produce a BokFxRecord.
 * Use {@link BokFxMapper#toRecord(CommittedTransaction)} which enforces this rule.
 */
public final class BokFxRecord {

    private final long txnId;
    private final String txnRef;
    private final BokReportType reportType;
    private final LocalDate reportDate;
    private final long partnerId;

    private final BigDecimal collectionAmount;
    private final String collectionCcy;
    private final BigDecimal payoutAmount;
    private final String payoutCcy;

    /**
     * BOK FX1015 field #14.
     * Formula: send_amount / (collection_usd - collection_margin_usd).
     * Scale: 8 decimal places, HALF_UP.
     * Not null for cross-border transactions.
     */
    private final BigDecimal offerRateColl;

    /** target_payout / send_amount. Scale 8, HALF_UP. Not null for cross-border. */
    private final BigDecimal crossRate;

    /** Intermediary USD amount (payout_usd_cost). */
    private final BigDecimal usdAmount;

    private final String submissionStatus;

    public BokFxRecord(
            long txnId,
            String txnRef,
            BokReportType reportType,
            LocalDate reportDate,
            long partnerId,
            BigDecimal collectionAmount,
            String collectionCcy,
            BigDecimal payoutAmount,
            String payoutCcy,
            BigDecimal offerRateColl,
            BigDecimal crossRate,
            BigDecimal usdAmount,
            String submissionStatus) {
        this.txnId = txnId;
        this.txnRef = txnRef;
        this.reportType = reportType;
        this.reportDate = reportDate;
        this.partnerId = partnerId;
        this.collectionAmount = collectionAmount;
        this.collectionCcy = collectionCcy;
        this.payoutAmount = payoutAmount;
        this.payoutCcy = payoutCcy;
        this.offerRateColl = offerRateColl;
        this.crossRate = crossRate;
        this.usdAmount = usdAmount;
        this.submissionStatus = submissionStatus;
    }

    public long getTxnId() { return txnId; }
    public String getTxnRef() { return txnRef; }
    public BokReportType getReportType() { return reportType; }
    public LocalDate getReportDate() { return reportDate; }
    public long getPartnerId() { return partnerId; }
    public BigDecimal getCollectionAmount() { return collectionAmount; }
    public String getCollectionCcy() { return collectionCcy; }
    public BigDecimal getPayoutAmount() { return payoutAmount; }
    public String getPayoutCcy() { return payoutCcy; }
    /** BOK FX1015 field #14. */
    public BigDecimal getOfferRateColl() { return offerRateColl; }
    public BigDecimal getCrossRate() { return crossRate; }
    public BigDecimal getUsdAmount() { return usdAmount; }
    public String getSubmissionStatus() { return submissionStatus; }
}
