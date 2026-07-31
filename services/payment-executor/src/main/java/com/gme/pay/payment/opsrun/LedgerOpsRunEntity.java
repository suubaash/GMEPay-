package com.gme.pay.payment.opsrun;

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
 * JPA entity mapping {@code ledger_ops_runs} (Flyway V008) — one row per run of one ledger-ops job, written
 * in its OWN transaction so a FAILED run survives the rollback of the work it describes (gap <b>T2-5</b>,
 * reusing the durability pattern settlement-reconciliation's {@code BatchRunEntity} established for T3-4).
 *
 * <p>Column widths are enforced in the setters rather than trusted: the failure message and stack excerpt
 * come from an arbitrary exception, and a run-ledger insert that itself failed on an oversized value would
 * reproduce the exact invisibility this table exists to remove.
 */
@Entity
@Table(name = "ledger_ops_runs")
public class LedgerOpsRunEntity {

    /** Matches {@code failure_message VARCHAR(1000)} and {@code summary VARCHAR(1000)}. */
    static final int MAX_1000 = 1000;
    /** Matches {@code failure_trace VARCHAR(4000)}. */
    static final int MAX_TRACE = 4000;
    /** Matches {@code failure_class VARCHAR(255)}. */
    static final int MAX_CLASS = 255;
    /** Matches {@code alert_error VARCHAR(500)}. */
    static final int MAX_500 = 500;
    /** Matches {@code operator_id VARCHAR(64)}. */
    static final int MAX_64 = 64;
    /** Matches {@code job VARCHAR(48)}. */
    static final int MAX_48 = 48;
    /** Matches {@code alert_status VARCHAR(20)}. */
    static final int MAX_20 = 20;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "job", length = MAX_48, nullable = false)
    private String job;

    /** Null for the replay sweeper, which is not date-scoped. */
    @Column(name = "business_date")
    private LocalDate businessDate;

    @Column(name = "outcome", length = 24, nullable = false)
    private String outcome;

    @Column(name = "trigger_source", length = 16, nullable = false)
    private String triggerSource;

    @Column(name = "operator_id", length = MAX_64)
    private String operatorId;

    @Column(name = "summary", length = MAX_1000)
    private String summary;

    @Column(name = "record_count")
    private Integer recordCount;

    @Column(name = "failure_class", length = MAX_CLASS)
    private String failureClass;

    @Column(name = "failure_message", length = MAX_1000)
    private String failureMessage;

    @Column(name = "failure_trace", length = MAX_TRACE)
    private String failureTrace;

    @Column(name = "alert_status", length = MAX_20)
    private String alertStatus;

    @Column(name = "alert_error", length = MAX_500)
    private String alertError;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at", nullable = false)
    private Instant finishedAt;

    public LedgerOpsRunEntity() {
        // JPA
    }

    public Long getId() {
        return id;
    }

    public String getJob() {
        return job;
    }

    public void setJob(String job) {
        this.job = truncate(job, MAX_48);
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
        this.outcome = truncate(outcome, 24);
    }

    public String getTriggerSource() {
        return triggerSource;
    }

    public void setTriggerSource(String triggerSource) {
        this.triggerSource = truncate(triggerSource, 16);
    }

    public String getOperatorId() {
        return operatorId;
    }

    public void setOperatorId(String operatorId) {
        this.operatorId = truncate(operatorId, MAX_64);
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = truncate(summary, MAX_1000);
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
        this.failureMessage = truncate(failureMessage, MAX_1000);
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
        this.alertStatus = truncate(alertStatus, MAX_20);
    }

    public String getAlertError() {
        return alertError;
    }

    public void setAlertError(String alertError) {
        this.alertError = truncate(alertError, MAX_500);
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
        if (this == o) {
            return true;
        }
        if (!(o instanceof LedgerOpsRunEntity that)) {
            return false;
        }
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
