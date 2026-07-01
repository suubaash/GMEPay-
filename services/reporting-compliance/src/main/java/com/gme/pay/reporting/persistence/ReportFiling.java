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
 * <p>Lifecycle: {@code PENDING → GENERATED → SUBMITTED → CONFIRMED} (or {@code FAILED}).
 * The external submission channels (BOK OI-03, Hometax OI-02) are externally blocked,
 * so in practice the record stays at {@code GENERATED} until a real channel is wired.
 */
@Entity
@Table(name = "report_filing",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_report_filing_natural_key",
                columnNames = {"lane", "report_type", "report_date"}))
public class ReportFiling {

    /** Regulatory lane this filing belongs to. */
    public enum Lane { BOK, KOFIU, HOMETAX }

    /** Submission lifecycle status. */
    public enum Status { PENDING, GENERATED, SUBMITTED, CONFIRMED, FAILED }

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

    @Column(name = "submission_status", nullable = false, length = 16)
    private String submissionStatus;

    @Column(name = "file_path", length = 512)
    private String filePath;

    @Column(name = "external_receipt_id", length = 128)
    private String externalReceiptId;

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

    /** Marks the filing SUBMITTED with the channel acknowledgement id. */
    public void markSubmitted(String externalReceiptId) {
        this.externalReceiptId = externalReceiptId;
        this.submissionStatus = Status.SUBMITTED.name();
        this.submittedAt = Instant.now();
        this.updatedAt = this.submittedAt;
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
    public Instant getGeneratedAt() { return generatedAt; }
    public Instant getSubmittedAt() { return submittedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
