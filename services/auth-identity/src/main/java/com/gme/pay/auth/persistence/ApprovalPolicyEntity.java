package com.gme.pay.auth.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * JPA mapping for the {@code approval_policies} table (V005__approval_workflow.sql).
 *
 * <p>A DB-driven tier rule: for a {@code requestType} + optional {@code currency}, an amount in
 * {@code [minAmount, maxAmount)} maps to an ordered list of approver permissions
 * ({@code stepPermissions}, CSV). Empty steps + {@code autoApprove} = self-service (no sign-off).
 * Tiers are configured here, never hardcoded.
 */
@Entity
@Table(name = "approval_policies")
public class ApprovalPolicyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_type", length = 32, nullable = false)
    private String requestType;

    /** NULL = matches any currency (wildcard fallback). */
    @Column(name = "currency", length = 3)
    private String currency;

    @Column(name = "min_amount", nullable = false, precision = 20, scale = 4)
    private BigDecimal minAmount;

    /** NULL = no upper bound. */
    @Column(name = "max_amount", precision = 20, scale = 4)
    private BigDecimal maxAmount;

    @Column(name = "tier_label", length = 32, nullable = false)
    private String tierLabel;

    /** Ordered CSV of required permission codes; empty = no approval step. */
    @Column(name = "step_permissions", length = 512, nullable = false)
    private String stepPermissions = "";

    @Column(name = "auto_approve", nullable = false)
    private boolean autoApprove;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ApprovalPolicyEntity() {
    }

    public ApprovalPolicyEntity(String requestType, String currency, BigDecimal minAmount,
                                BigDecimal maxAmount, String tierLabel, String stepPermissions,
                                boolean autoApprove, boolean active, Instant createdAt) {
        this.requestType = requestType;
        this.currency = currency;
        this.minAmount = minAmount;
        this.maxAmount = maxAmount;
        this.tierLabel = tierLabel;
        this.stepPermissions = stepPermissions == null ? "" : stepPermissions;
        this.autoApprove = autoApprove;
        this.active = active;
        this.createdAt = createdAt;
    }

    /** True if {@code amount} falls in this policy's band [minAmount, maxAmount). */
    public boolean matchesAmount(BigDecimal amount) {
        if (amount.compareTo(minAmount) < 0) {
            return false;
        }
        return maxAmount == null || amount.compareTo(maxAmount) < 0;
    }

    /** The ordered step permission codes (empty list when no steps configured). */
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

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getMinAmount() {
        return minAmount;
    }

    public BigDecimal getMaxAmount() {
        return maxAmount;
    }

    public String getTierLabel() {
        return tierLabel;
    }

    public String getStepPermissions() {
        return stepPermissions;
    }

    public boolean isAutoApprove() {
        return autoApprove;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
