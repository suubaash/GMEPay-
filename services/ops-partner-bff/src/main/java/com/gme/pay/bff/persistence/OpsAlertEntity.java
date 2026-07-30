package com.gme.pay.bff.persistence;

import com.gme.pay.bff.alert.OpsAlertView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One consumed ops alert plus its paging record and operator acknowledgement (table
 * {@code ops_alerts}, Flyway V001).
 *
 * <p>This row is what lifted ops-partner-bff's single-replica ceiling. It replaces a per-JVM
 * {@code ArrayDeque} whose {@code seq} came from a per-JVM {@code AtomicLong}: at N&gt;1 the alerts
 * list, its ack state and even the alert <em>ids</em> differed per replica. {@code seq} is now
 * allocated by one database sequence.
 *
 * <p>{@code occurredAt}, {@code pagingLastAt} and {@code ackAt} are stored as the producer's /
 * writer's own strings rather than timestamps — see the migration: an alert must never be rejected
 * (i.e. lost) because a producer's {@code occurredAt} is not an ISO-8601 instant, and the view type
 * they map to carries strings.
 *
 * <p>Mutable only through {@link #applyPaging} / {@link #applyAck}, both called inside a transaction
 * that holds the row exclusively, so a concurrent ack and paging stamp cannot lose one another's
 * fields — the exact failure mode that made a Redis hash the wrong shape for this store.
 */
@Entity
@Table(name = "ops_alerts")
public class OpsAlertEntity {

    private static final int MAX_DETAIL_LEN = 1024;
    private static final int MAX_SUBJECT_LEN = 128;
    private static final int MAX_TYPE_LEN = 64;
    private static final int MAX_SEVERITY_LEN = 16;
    private static final int MAX_SHORT_TEXT_LEN = 64;
    private static final int MAX_ACTOR_LEN = 128;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "seq")
    private Long seq;

    @Column(name = "alert_type", nullable = false, length = MAX_TYPE_LEN)
    private String alertType;

    @Column(name = "severity", nullable = false, length = MAX_SEVERITY_LEN)
    private String severity;

    @Column(name = "subject_ref", length = MAX_SUBJECT_LEN)
    private String subjectRef;

    @Column(name = "detail")
    private String detail;

    @Column(name = "occurred_at", length = MAX_SHORT_TEXT_LEN)
    private String occurredAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "paging_status", length = MAX_SEVERITY_LEN)
    private String pagingStatus;

    @Column(name = "paging_channel", length = MAX_SHORT_TEXT_LEN)
    private String pagingChannel;

    @Column(name = "paging_attempts", nullable = false)
    private int pagingAttempts;

    @Column(name = "paging_last_at", length = MAX_SHORT_TEXT_LEN)
    private String pagingLastAt;

    @Column(name = "paging_detail", length = MAX_DETAIL_LEN)
    private String pagingDetail;

    @Column(name = "ack_operator", length = MAX_ACTOR_LEN)
    private String ackOperator;

    @Column(name = "ack_note", length = MAX_DETAIL_LEN)
    private String ackNote;

    @Column(name = "ack_at", length = MAX_SHORT_TEXT_LEN)
    private String ackAt;

    protected OpsAlertEntity() {
        // JPA
    }

    public OpsAlertEntity(String alertType, String severity, String subjectRef, String detail,
                          String occurredAt, Instant createdAt) {
        // alertType/severity are NOT NULL in the DDL, and a producer that omits one must not make
        // the insert fail (that would drop the alert). "UNKNOWN" is the honest stand-in; it is also
        // a value no filter matches by accident.
        this.alertType = truncate(blankToDefault(alertType, "UNKNOWN"), MAX_TYPE_LEN);
        this.severity = truncate(blankToDefault(severity, "UNKNOWN"), MAX_SEVERITY_LEN);
        this.subjectRef = truncate(subjectRef, MAX_SUBJECT_LEN);
        this.detail = detail;
        this.occurredAt = truncate(occurredAt, MAX_SHORT_TEXT_LEN);
        this.createdAt = createdAt;
        this.pagingAttempts = 0;
    }

    /** Overwrite the paging record. A null record clears it (never observed in practice). */
    public void applyPaging(OpsAlertView.Paging paging) {
        if (paging == null) {
            this.pagingStatus = null;
            this.pagingChannel = null;
            this.pagingAttempts = 0;
            this.pagingLastAt = null;
            this.pagingDetail = null;
            return;
        }
        this.pagingStatus = truncate(paging.status(), MAX_SEVERITY_LEN);
        this.pagingChannel = truncate(paging.channel(), MAX_SHORT_TEXT_LEN);
        this.pagingAttempts = paging.attempts();
        this.pagingLastAt = truncate(paging.lastAt(), MAX_SHORT_TEXT_LEN);
        this.pagingDetail = truncate(paging.detail(), MAX_DETAIL_LEN);
    }

    /** Overwrite the acknowledgement. A null record clears it (never observed in practice). */
    public void applyAck(OpsAlertView.Ack ack) {
        if (ack == null) {
            this.ackOperator = null;
            this.ackNote = null;
            this.ackAt = null;
            return;
        }
        this.ackOperator = truncate(ack.operator(), MAX_ACTOR_LEN);
        this.ackNote = truncate(ack.note(), MAX_DETAIL_LEN);
        // ack_at is what marks the alert acknowledged, so it must never be null on an acked row.
        this.ackAt = truncate(
                (ack.at() == null || ack.at().isBlank()) ? Instant.now().toString() : ack.at(),
                MAX_SHORT_TEXT_LEN);
    }

    /** Project to the wire/read view the controllers and the escalation sweep work with. */
    public OpsAlertView toView() {
        OpsAlertView.Paging paging = pagingStatus == null
                ? null
                : new OpsAlertView.Paging(pagingStatus, pagingChannel, pagingAttempts,
                        pagingLastAt, pagingDetail);
        OpsAlertView.Ack ack = (ackAt == null && ackOperator == null)
                ? null
                : new OpsAlertView.Ack(ackOperator, ackNote, ackAt);
        return new OpsAlertView(seq == null ? 0L : seq, alertType, severity, subjectRef, detail,
                occurredAt, paging, ack);
    }

    public Long getSeq() {
        return seq;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    private static String blankToDefault(String s, String fallback) {
        return (s == null || s.isBlank()) ? fallback : s;
    }

    /**
     * Truncate rather than reject. A producer sending an over-long subjectRef or detail must not
     * cause the insert to fail — that would be an alert dropped by a column width.
     */
    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
