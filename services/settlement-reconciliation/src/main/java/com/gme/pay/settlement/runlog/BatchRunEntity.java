package com.gme.pay.settlement.runlog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * JPA entity mapping {@code batch_runs} (Flyway V012) — one row per run of one settlement batch window,
 * written in its own transaction so it survives the rollback of the run it describes (gap <b>T3-4</b>).
 *
 * <p>Column widths are enforced in the setters rather than trusted: the failure message and stack excerpt
 * come from an arbitrary exception, and a run-ledger insert that itself fails on a too-long value would
 * reproduce the exact invisibility this table exists to remove.
 *
 * @see BatchRunRecorder
 */
@Entity
@Table(name = "batch_runs")
public class BatchRunEntity {

    /** Matches {@code failure_message VARCHAR(1000)}. */
    static final int MAX_MESSAGE = 1000;
    /** Matches {@code failure_trace VARCHAR(4000)}. */
    static final int MAX_TRACE = 4000;
    /** Matches {@code failure_class VARCHAR(255)}. */
    static final int MAX_CLASS = 255;
    /** Matches {@code alert_error VARCHAR(500)} and {@code reason VARCHAR(500)}. */
    static final int MAX_500 = 500;
    /** Matches {@code operator_id VARCHAR(64)} / {@code batch_id VARCHAR(64)}. */
    static final int MAX_64 = 64;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "run_kind", length = 24, nullable = false)
    private String runKind;

    @Column(name = "file_type", length = 16, nullable = false)
    private String fileType;

    @Column(name = "settlement_window", length = 16, nullable = false)
    private String settlementWindow;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "outcome", length = 32, nullable = false)
    private String outcome;

    @Column(name = "trigger_source", length = 24, nullable = false)
    private String triggerSource;

    @Column(name = "calendar_verdict", length = 24, nullable = false)
    private String calendarVerdict;

    @Column(name = "batch_id", length = 64)
    private String batchId;

    @Column(name = "batch_status", length = 24)
    private String batchStatus;

    @Column(name = "record_count")
    private Integer recordCount;

    @Column(name = "failure_class", length = MAX_CLASS)
    private String failureClass;

    @Column(name = "failure_message", length = MAX_MESSAGE)
    private String failureMessage;

    @Column(name = "failure_trace", length = MAX_TRACE)
    private String failureTrace;

    @Column(name = "alert_status", length = 16)
    private String alertStatus;

    @Column(name = "alert_error", length = MAX_500)
    private String alertError;

    @Column(name = "operator_id", length = MAX_64)
    private String operatorId;

    @Column(name = "reason", length = MAX_500)
    private String reason;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at", nullable = false)
    private Instant finishedAt;

    public BatchRunEntity() {
        // JPA
    }

    public Long getId() {
        return id;
    }

    public String getRunKind() {
        return runKind;
    }

    public void setRunKind(String runKind) {
        this.runKind = truncate(runKind, 24);
    }

    public String getFileType() {
        return fileType;
    }

    public void setFileType(String fileType) {
        this.fileType = truncate(fileType, 16);
    }

    public String getSettlementWindow() {
        return settlementWindow;
    }

    public void setSettlementWindow(String settlementWindow) {
        this.settlementWindow = truncate(settlementWindow, 16);
    }

    public LocalDate getBusinessDate() {
        return businessDate;
    }

    public void setBusinessDate(LocalDate businessDate) {
        this.businessDate = businessDate;
    }

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String outcome) {
        this.outcome = truncate(outcome, 32);
    }

    public String getTriggerSource() {
        return triggerSource;
    }

    public void setTriggerSource(String triggerSource) {
        this.triggerSource = truncate(triggerSource, 24);
    }

    public String getCalendarVerdict() {
        return calendarVerdict;
    }

    public void setCalendarVerdict(String calendarVerdict) {
        this.calendarVerdict = truncate(calendarVerdict, 24);
    }

    public String getBatchId() {
        return batchId;
    }

    public void setBatchId(String batchId) {
        this.batchId = truncate(batchId, MAX_64);
    }

    public String getBatchStatus() {
        return batchStatus;
    }

    public void setBatchStatus(String batchStatus) {
        this.batchStatus = truncate(batchStatus, 24);
    }

    public Integer getRecordCount() {
        return recordCount;
    }

    public void setRecordCount(Integer recordCount) {
        this.recordCount = recordCount;
    }

    public String getFailureClass() {
        return failureClass;
    }

    public void setFailureClass(String failureClass) {
        this.failureClass = truncate(failureClass, MAX_CLASS);
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    public void setFailureMessage(String failureMessage) {
        this.failureMessage = truncate(failureMessage, MAX_MESSAGE);
    }

    public String getFailureTrace() {
        return failureTrace;
    }

    public void setFailureTrace(String failureTrace) {
        this.failureTrace = truncate(failureTrace, MAX_TRACE);
    }

    public String getAlertStatus() {
        return alertStatus;
    }

    public void setAlertStatus(String alertStatus) {
        this.alertStatus = truncate(alertStatus, 16);
    }

    public String getAlertError() {
        return alertError;
    }

    public void setAlertError(String alertError) {
        this.alertError = truncate(alertError, MAX_500);
    }

    public String getOperatorId() {
        return operatorId;
    }

    public void setOperatorId(String operatorId) {
        this.operatorId = truncate(operatorId, MAX_64);
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = truncate(reason, MAX_500);
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    /** Null-safe, width-safe truncation — the ledger insert must never fail on an oversized value. */
    static String truncate(String v, int max) {
        if (v == null) {
            return null;
        }
        return v.length() <= max ? v : v.substring(0, max);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BatchRunEntity that)) return false;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
