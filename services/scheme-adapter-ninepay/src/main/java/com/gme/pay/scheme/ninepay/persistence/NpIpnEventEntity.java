package com.gme.pay.scheme.ninepay.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One inbound 9Pay IPN push, recorded verbatim — maps {@code np_ipn_events} (V001).
 *
 * <p>Every push is persisted BEFORE any payout mutation, including ones whose RSA
 * signature failed verification ({@code signature_valid=false}) and ones referencing an
 * unknown {@code request_id} — the table is the audit/replay trail, and for message code
 * 009 (bank reversal after SUCCESS) the event row IS the reversal record.</p>
 */
@Entity
@Table(name = "np_ipn_events")
public class NpIpnEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", nullable = false, length = 50)
    private String requestId;

    @Column(name = "trans_id", length = 20)
    private String transId;

    @Column(name = "code", length = 3)
    private String code;

    @Column(name = "status", length = 50)
    private String status;

    @Column(name = "raw_payload", nullable = false)
    private String rawPayload;

    @Column(name = "signature_valid", nullable = false)
    private boolean signatureValid;

    /**
     * Replay-guard key (V002, T5-4): a hash of this IPN's own event identity
     * ({@code request_id|trans_id|code}), UNIQUE across the table. {@code null} for rows
     * that must not claim the identity — signature failures (9Pay will retry) and audited
     * replays — since both engines permit repeated NULLs under a UNIQUE constraint.
     */
    @Column(name = "event_key", length = 64)
    private String eventKey;

    /** Whether this event actually mutated the payout, as opposed to being audited only. */
    @Column(name = "applied", nullable = false)
    private boolean applied;

    /** Why the event was not applied ({@link IpnRejectReason}); {@code null} when applied. */
    @Column(name = "reject_reason", length = 40)
    private String rejectReason;

    /**
     * The IPN's own SIGNED {@code created_at} (Y-m-d H:i:s, GMT+7) — V002, T5-4. Ordering
     * input for the staleness rule; unlike {@link #receivedAt} it cannot be influenced by
     * when an attacker chooses to replay.
     */
    @Column(name = "scheme_created_at", length = 20)
    private String schemeCreatedAt;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected NpIpnEventEntity() {
        // JPA
    }

    public NpIpnEventEntity(String requestId, String transId, String code, String status,
                            String rawPayload, boolean signatureValid) {
        this.requestId = requestId;
        this.transId = transId;
        this.code = code;
        this.status = status;
        this.rawPayload = rawPayload;
        this.signatureValid = signatureValid;
    }

    /** Marks this row as the one applied event for its identity (T5-4). */
    public void markApplied(String eventKey) {
        this.eventKey = eventKey;
        this.applied = true;
        this.rejectReason = null;
    }

    /**
     * Marks this row as audited-but-not-applied. {@code eventKey} stays null so a genuine
     * later delivery of the same identity is not blocked by a rejected one.
     */
    public void markRejected(IpnRejectReason reason) {
        this.eventKey = null;
        this.applied = false;
        this.rejectReason = reason == null ? null : reason.name();
    }

    @PrePersist
    void onPersist() {
        if (receivedAt == null) {
            receivedAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getTransId() {
        return transId;
    }

    public String getCode() {
        return code;
    }

    public String getStatus() {
        return status;
    }

    public String getRawPayload() {
        return rawPayload;
    }

    public boolean isSignatureValid() {
        return signatureValid;
    }

    public String getEventKey() {
        return eventKey;
    }

    public boolean isApplied() {
        return applied;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    public String getSchemeCreatedAt() {
        return schemeCreatedAt;
    }

    public void setSchemeCreatedAt(String schemeCreatedAt) {
        this.schemeCreatedAt = schemeCreatedAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
