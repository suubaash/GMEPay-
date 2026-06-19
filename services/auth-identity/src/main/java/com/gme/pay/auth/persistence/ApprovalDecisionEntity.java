package com.gme.pay.auth.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * JPA mapping for the {@code approval_decisions} table (V005__approval_workflow.sql) — the
 * per-step audit trail of who approved/rejected an {@link ApprovalRequestEntity} and under what
 * authority. {@code cfoOverride} marks a CFO break-glass decision (the approver satisfied the
 * step via the superuser / CFO-override grant rather than the step's own permission).
 */
@Entity
@Table(name = "approval_decisions")
public class ApprovalDecisionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", nullable = false)
    private Long requestId;

    @Column(name = "step_index", nullable = false)
    private int stepIndex;

    /** The permission the step demanded; NULL when satisfied by CFO override / auto. */
    @Column(name = "required_permission", length = 128)
    private String requiredPermission;

    @Column(name = "approver_id", length = 128, nullable = false)
    private String approverId;

    @Column(name = "decision", length = 16, nullable = false)
    private String decision;

    @Column(name = "cfo_override", nullable = false)
    private boolean cfoOverride;

    @Column(name = "reason", length = 512)
    private String reason;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;

    protected ApprovalDecisionEntity() {
    }

    public ApprovalDecisionEntity(Long requestId, int stepIndex, String requiredPermission, String approverId,
                                  String decision, boolean cfoOverride, String reason, Instant decidedAt) {
        this.requestId = requestId;
        this.stepIndex = stepIndex;
        this.requiredPermission = requiredPermission;
        this.approverId = approverId;
        this.decision = decision;
        this.cfoOverride = cfoOverride;
        this.reason = reason;
        this.decidedAt = decidedAt;
    }

    public Long getId() {
        return id;
    }

    public Long getRequestId() {
        return requestId;
    }

    public int getStepIndex() {
        return stepIndex;
    }

    public String getRequiredPermission() {
        return requiredPermission;
    }

    public String getApproverId() {
        return approverId;
    }

    public String getDecision() {
        return decision;
    }

    public boolean isCfoOverride() {
        return cfoOverride;
    }

    public String getReason() {
        return reason;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }
}
