package com.gme.pay.reporting.kofiu;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Suspicious Transaction Report (STR) for a single transaction on a
 * STR-enabled corridor.
 *
 * <p>KoFIU FTRA Article 5 requires an STR when a transaction on a corridor that
 * has been designated as STR-monitored ({@code str_enabled = TRUE} on the
 * V029_1 corridor config) is committed. STR is per-transaction, not aggregated.
 *
 * <p>The STR flag is a corridor-level gate, not a per-transaction suspicious-
 * indicator: all transactions on a flagged corridor are reported. This is the
 * reporting-infrastructure layer; actual suspicious-activity analysis is out
 * of scope for this service.
 */
public final class StrReport {

    /** Report type discriminator on the feed file. */
    public static final String REPORT_TYPE = "STR";

    private final long txnId;
    private final String txnRef;
    private final String endUserId;
    private final long partnerId;
    private final LocalDate reportDate;

    /**
     * Transaction amount in KRW.
     * BigDecimal; never double/float per MONEY_CONVENTION.md.
     */
    private final BigDecimal amountKrw;

    /** Source currency (ISO-4217). */
    private final String srcCcy;

    /** Destination currency (ISO-4217). */
    private final String dstCcy;

    public StrReport(
            long txnId,
            String txnRef,
            String endUserId,
            long partnerId,
            LocalDate reportDate,
            BigDecimal amountKrw,
            String srcCcy,
            String dstCcy) {
        this.txnId = txnId;
        this.txnRef = txnRef;
        this.endUserId = endUserId;
        this.partnerId = partnerId;
        this.reportDate = reportDate;
        this.amountKrw = amountKrw;
        this.srcCcy = srcCcy;
        this.dstCcy = dstCcy;
    }

    public long getTxnId() { return txnId; }
    public String getTxnRef() { return txnRef; }
    public String getEndUserId() { return endUserId; }
    public long getPartnerId() { return partnerId; }
    public LocalDate getReportDate() { return reportDate; }
    public BigDecimal getAmountKrw() { return amountKrw; }
    public String getSrcCcy() { return srcCcy; }
    public String getDstCcy() { return dstCcy; }
}
