package com.gme.pay.reporting.kofiu;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Currency Transaction Report (CTR) for a single end-user on a single KST day.
 *
 * <p>KoFIU FTRA Article 4 requires a CTR when a single end-user customer's
 * total KRW cash-equivalent transaction volume in one KST calendar day meets or
 * exceeds the configurable {@code ctrThresholdKrw} (statutory default
 * KRW 10,000,000).
 *
 * <p>This is an aggregated report, not a per-transaction record. The
 * {@code contributingTxnIds} list is retained for auditability.
 */
public final class CtrReport {

    /** Report type discriminator on the feed file. */
    public static final String REPORT_TYPE = "CTR";

    private final String endUserId;
    private final long partnerId;
    private final LocalDate reportDate;

    /**
     * Total KRW-equivalent amount across all contributing transactions.
     * BigDecimal; never double/float per MONEY_CONVENTION.md.
     */
    private final BigDecimal totalAmountKrw;

    /** Number of individual transactions that make up the aggregate. */
    private final int transactionCount;

    /** Ordered list of transaction ids that contributed to this CTR (audit trail). */
    private final List<Long> contributingTxnIds;

    public CtrReport(
            String endUserId,
            long partnerId,
            LocalDate reportDate,
            BigDecimal totalAmountKrw,
            int transactionCount,
            List<Long> contributingTxnIds) {
        this.endUserId = endUserId;
        this.partnerId = partnerId;
        this.reportDate = reportDate;
        this.totalAmountKrw = totalAmountKrw;
        this.transactionCount = transactionCount;
        this.contributingTxnIds = List.copyOf(contributingTxnIds);
    }

    public String getEndUserId() { return endUserId; }
    public long getPartnerId() { return partnerId; }
    public LocalDate getReportDate() { return reportDate; }
    public BigDecimal getTotalAmountKrw() { return totalAmountKrw; }
    public int getTransactionCount() { return transactionCount; }
    public List<Long> getContributingTxnIds() { return contributingTxnIds; }
}
