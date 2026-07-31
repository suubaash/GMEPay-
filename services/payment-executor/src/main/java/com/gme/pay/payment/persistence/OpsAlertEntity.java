package com.gme.pay.payment.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One ops alert emitted by this service (table {@code ops_alerts}, Flyway V006 — gap T3-3 / COO#4).
 *
 * <p>Before this row existed, an alert's entire lifetime was a log line in a container with no log
 * aggregation, plus (when a broker happened to be wired) a slot in ops-partner-bff's 200-entry
 * in-memory deque that emptied on restart. A decline spike therefore left no evidence anyone could
 * query the next morning. The row is written <b>before</b> the alert is published or notified, so it
 * exists even if the broker, the BFF and the notification target are all down.
 *
 * <p>No natural key / no dedupe constraint, on purpose: each firing is a distinct historical event and
 * repeat suppression already happens upstream in {@code DeclineSpikeMonitor}'s per-subject cooldown.
 * The notification columns are stamped in place once the sink reports back, so one row answers both
 * "what fired?" and "did anyone find out?".
 */
@Entity
@Table(name = "ops_alerts")
public class OpsAlertEntity {

    /** Written, sink not yet attempted (only visible mid-flight). */
    public static final String NOTIFY_PENDING = "PENDING";
    /** The notification sink accepted the alert. */
    public static final String NOTIFY_DELIVERED = "DELIVERED";
    /** The notification sink failed after its own retries. */
    public static final String NOTIFY_FAILED = "FAILED";
    /** No sink ran for this row. */
    public static final String NOTIFY_SKIPPED = "SKIPPED";

    private static final int MAX_ERROR_LEN = 1024;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "alert_type", nullable = false, length = 64)
    private String alertType;

    @Column(name = "severity", nullable = false, length = 16)
    private String severity;

    @Column(name = "subject_ref", nullable = false, length = 128)
    private String subjectRef;

    @Column(name = "detail")
    private String detail;

    /** When the monitor decided the alert fired (its clock), not when this row was written. */
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "notify_status", nullable = false, length = 16)
    private String notifyStatus = NOTIFY_PENDING;

    @Column(name = "notify_channel", length = 32)
    private String notifyChannel;

    @Column(name = "notify_error", length = MAX_ERROR_LEN)
    private String notifyError;

    protected OpsAlertEntity() {
        // JPA
    }

    public OpsAlertEntity(String alertType, String severity, String subjectRef, String detail,
                          Instant occurredAt, Instant createdAt) {
        this.alertType = alertType;
        this.severity = severity;
        this.subjectRef = subjectRef;
        this.detail = detail;
        this.occurredAt = occurredAt;
        this.createdAt = createdAt;
        this.notifyStatus = NOTIFY_PENDING;
    }

    /** Stamp the outcome of the outbound notification attempt onto this row. */
    public void recordNotification(String status, String channel, String error) {
        this.notifyStatus = status;
        this.notifyChannel = channel;
        this.notifyError = truncate(error);
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= MAX_ERROR_LEN ? s : s.substring(0, MAX_ERROR_LEN);
    }

    public Long getId() {
        return id;
    }

    public String getAlertType() {
        return alertType;
    }

    public String getSeverity() {
        return severity;
    }

    public String getSubjectRef() {
        return subjectRef;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getNotifyStatus() {
        return notifyStatus;
    }

    public String getNotifyChannel() {
        return notifyChannel;
    }

    public String getNotifyError() {
        return notifyError;
    }
}
