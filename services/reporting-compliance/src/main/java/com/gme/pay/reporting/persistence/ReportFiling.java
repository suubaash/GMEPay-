package com.gme.pay.reporting.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.time.LocalDate;

/**
 * JPA entity for {@code report_filing} (V001) — one row per
 * (lane, report_type, report_date) regulatory filing run.
 *
 * <p>This is the owned-datastore record of every report this service generates
 * and (eventually) submits. The natural key {@code (lane, report_type, report_date)}
 * is UNIQUE, giving schedulers idempotency on re-run.
 *
 * <p>Lifecycle — see {@link Status}. The three facts "we built the report", "we
 * transmitted it" and "the authority acknowledged it" are separate states, because for
 * every lane today only the first is true: no regulatory transmission channel exists
 * (GAP T5-2). A filing on a lane without a channel terminates at
 * {@link Status#NOT_FILED_CHANNEL_UNAVAILABLE}, and
 * {@link ReportFilingService#recordTransmission} refuses to write
 * {@link Status#TRANSMITTED} unless
 * {@link com.gme.pay.reporting.channel.FilingChannelRegistry} reports a live channel.
 */
@Entity
@Table(name = "report_filing",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_report_filing_natural_key",
                columnNames = {"lane", "report_type", "report_date"}))
public class ReportFiling {

    /** Regulatory lane this filing belongs to. */
    public enum Lane { BOK, KOFIU, HOMETAX }

    /**
     * Filing lifecycle status. Deliberately splits local work from transmission from
     * acknowledgement so that no state can imply a regulator saw the report when none did.
     *
     * <p>Reachable today for all three lanes: {@code PENDING → GENERATED → VALIDATED →
     * NOT_FILED_CHANNEL_UNAVAILABLE}. {@code TRANSMITTED} and {@code ACKNOWLEDGED} are
     * unreachable until a lane has a configured channel — enforced in
     * {@link ReportFilingService}, not merely documented.
     */
    public enum Status {

        /** Filing row opened for (lane, type, date); no data aggregated yet. */
        PENDING,

        /** Aggregation ran and the report artifact was produced locally. REAL capability. */
        GENERATED,

        /**
         * The generated artifact passed this service's own format checks (non-empty,
         * no unresolved placeholder tokens). Local validation only — it does <b>not</b>
         * mean the authority has confirmed the layout.
         */
        VALIDATED,

        /**
         * Terminal state when the lane has no transmission channel: the report exists and
         * is valid locally, but nothing was sent and no regulator has seen it. This is the
         * honest end state for BOK, KoFIU and Hometax today.
         */
        NOT_FILED_CHANNEL_UNAVAILABLE,

        /**
         * The artifact was accepted by a real channel. Requires a live channel for the
         * lane; cannot be written otherwise.
         */
        TRANSMITTED,

        /**
         * The authority returned a receipt for the transmitted artifact. Requires a live
         * channel for the lane; cannot be written otherwise.
         */
        ACKNOWLEDGED,

        /** The run failed (aggregation, generation or transmission error). */
        FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "lane", nullable = false, length = 16)
    private String lane;

    @Column(name = "report_type", nullable = false, length = 16)
    private String reportType;

    @Column(name = "report_date", nullable = false)
    private LocalDate reportDate;

    @Column(name = "record_count", nullable = false)
    private int recordCount;

    @Column(name = "submission_status", nullable = false, length = 32)
    private String submissionStatus;

    @Column(name = "file_path", length = 512)
    private String filePath;

    /**
     * Receipt id issued by the authority. MUST stay null unless a real channel returned
     * one — V003 nulled out the historical fabricated values.
     */
    @Column(name = "external_receipt_id", length = 128)
    private String externalReceiptId;

    /**
     * Non-null when the filing cannot be transmitted: names the missing channel
     * configuration (see {@link com.gme.pay.reporting.channel.FilingChannelRegistry}).
     */
    @Column(name = "channel_unavailable_reason", length = 512)
    private String channelUnavailableReason;

    /** Previous status value when V003 reclassified a fabricated acceptance. Audit trail. */
    @Column(name = "reclassified_from", length = 32)
    private String reclassifiedFrom;

    /** Why the reclassification happened. Audit trail; never overwritten by the app. */
    @Column(name = "reclassification_note", length = 512)
    private String reclassificationNote;

    @Column(name = "generated_at")
    private Instant generatedAt;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ReportFiling() {
        // JPA
    }

    public ReportFiling(Lane lane, String reportType, LocalDate reportDate) {
        this.lane = lane.name();
        this.reportType = reportType;
        this.reportDate = reportDate;
        this.recordCount = 0;
        this.submissionStatus = Status.PENDING.name();
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Marks the filing GENERATED with its record count and artifact path. */
    public void markGenerated(int recordCount, String filePath) {
        this.recordCount = recordCount;
        this.filePath = filePath;
        this.submissionStatus = Status.GENERATED.name();
        this.generatedAt = Instant.now();
        this.updatedAt = this.generatedAt;
    }

    /**
     * Marks the generated artifact VALIDATED against this service's own format checks.
     * Local validation only — not an authority confirmation.
     */
    public void markValidated() {
        this.submissionStatus = Status.VALIDATED.name();
        this.updatedAt = Instant.now();
    }

    /**
     * Terminal state for a lane with no transmission channel. Records the reason so the
     * register itself says why nothing was filed, instead of showing a success.
     * Never sets {@code submittedAt} or {@code externalReceiptId} — nothing was sent.
     */
    public void markChannelUnavailable(String reason) {
        this.submissionStatus = Status.NOT_FILED_CHANNEL_UNAVAILABLE.name();
        this.channelUnavailableReason = reason;
        this.updatedAt = Instant.now();
    }

    /**
     * Marks the filing TRANSMITTED with the receipt id returned by the real channel.
     * Callers must go through {@link ReportFilingService#recordTransmission}, which
     * refuses this transition when the lane has no live channel.
     */
    public void markTransmitted(String externalReceiptId) {
        this.externalReceiptId = externalReceiptId;
        this.submissionStatus = Status.TRANSMITTED.name();
        this.channelUnavailableReason = null;
        this.submittedAt = Instant.now();
        this.updatedAt = this.submittedAt;
    }

    /** Marks the filing ACKNOWLEDGED by the authority. */
    public void markAcknowledged(String externalReceiptId) {
        if (externalReceiptId != null) {
            this.externalReceiptId = externalReceiptId;
        }
        this.submissionStatus = Status.ACKNOWLEDGED.name();
        this.channelUnavailableReason = null;
        this.updatedAt = Instant.now();
    }

    /** Marks the filing FAILED. */
    public void markFailed() {
        this.submissionStatus = Status.FAILED.name();
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getLane() { return lane; }
    public String getReportType() { return reportType; }
    public LocalDate getReportDate() { return reportDate; }
    public int getRecordCount() { return recordCount; }
    public String getSubmissionStatus() { return submissionStatus; }
    public String getFilePath() { return filePath; }
    public String getExternalReceiptId() { return externalReceiptId; }
    public String getChannelUnavailableReason() { return channelUnavailableReason; }
    public String getReclassifiedFrom() { return reclassifiedFrom; }
    public String getReclassificationNote() { return reclassificationNote; }
    public Instant getGeneratedAt() { return generatedAt; }
    public Instant getSubmittedAt() { return submittedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
