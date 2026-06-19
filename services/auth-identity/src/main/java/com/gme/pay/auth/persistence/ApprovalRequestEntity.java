package com.gme.pay.auth.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * JPA mapping for the {@code approval_requests} table (V005__approval_workflow.sql) — one
 * pending/decided sign-off for a high-risk operation. {@code status} drives the lightweight FSM
 * (PENDING → APPROVED | REJECTED; AUTO_APPROVED is a terminal self-service outcome). Sequential
 * multi-level progress is tracked by {@code currentStep}/{@code requiredSteps}; per-step approver
 * audit lives in {@link ApprovalDecisionEntity}. Stored as a String + CHECK (cf. PG/H2 parity).
 */
@Entity
@Table(name = "approval_requests")
public class ApprovalRequestEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_type", length = 32, nullable = false)
    private String requestType;

    @Column(name = "subject_ref", length = 128, nullable = false)
    private String subjectRef;

    @Column(name = "amount", nullable = false, precision = 20, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Column(name = "tier_label", length = 32, nullable = false)
    private String tierLabel;

    @Column(name = "status", length = 16, nullable = false)
    private String status;

    /** Ordered CSV of required approver permissions, snapshotted from the policy at request time. */
    @Column(name = "step_permissions", length = 512, nullable = false)
    private String stepPermissions = "";

    @Column(name = "required_steps", nullable = false)
    private int requiredSteps;

    @Column(name = "current_step", nullable = false)
    private int currentStep;

    /** Optimistic-lock guard: a stale concurrent decide fails on flush (mapped to 409). */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "requested_by", length = 128, nullable = false)
    private String requestedBy;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "reject_reason", length = 512)
    private String rejectReason;

    @Column(name = "tenant_id")
    private Long tenantId;

    protected ApprovalRequestEntity() {
    }

    public ApprovalRequestEntity(String requestType, String subjectRef, BigDecimal amount, String currency,
                                 String tierLabel, String status, String stepPermissions, int requiredSteps,
                                 int currentStep, String requestedBy, Instant requestedAt, Long tenantId) {
        this.requestType = requestType;
        this.subjectRef = subjectRef;
        this.amount = amount;
        this.currency = currency;
        this.tierLabel = tierLabel;
        this.status = status;
        this.stepPermissions = stepPermissions == null ? "" : stepPermissions;
        this.requiredSteps = requiredSteps;
        this.currentStep = currentStep;
        this.requestedBy = requestedBy;
        this.requestedAt = requestedAt;
        this.tenantId = tenantId;
    }

    /** The ordered step permission codes snapshotted at request time (empty when none). */
    public List<String> steps() {
        if (stepPermissions == null || stepPermissions.isBlank()) {
            return List.of();
        }
        return Arrays.stream(stepPermissions.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public Long getId() {
        return id;
    }

    public String getRequestType() {
        return requestType;
    }

    public String getSubjectRef() {
        return subjectRef;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getTierLabel() {
        return tierLabel;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getStepPermissions() {
        return stepPermissions;
    }

    public int getRequiredSteps() {
        return requiredSteps;
    }

    public int getCurrentStep() {
        return currentStep;
    }

    public void setCurrentStep(int currentStep) {
        this.currentStep = currentStep;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(Instant decidedAt) {
        this.decidedAt = decidedAt;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    public void setRejectReason(String rejectReason) {
        this.rejectReason = rejectReason;
    }

    public Long getTenantId() {
        return tenantId;
    }
}
