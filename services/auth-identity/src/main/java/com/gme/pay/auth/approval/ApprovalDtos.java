package com.gme.pay.auth.approval;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * DTOs for the approval-workflow API ({@link ApprovalController}). The request view carries the
 * computed tier, FSM status, sequential-step progress, and the per-step decision trail so the
 * approval-queue dashboard composes from a single read.
 */
public final class ApprovalDtos {

    private ApprovalDtos() {}

    /** Service-layer command to open an approval request (requester identity from the edge). */
    public record RequestApprovalCommand(String requestType, String subjectRef, BigDecimal amount,
                                         String currency, String requestedBy, Long tenantId) {}

    /** POST /v1/approvals body — requester identity comes from the stamped X-Gme-Principal-Id. */
    public record CreateApprovalRequest(String requestType, String subjectRef, BigDecimal amount,
                                        String currency, Long tenantId) {}

    /** approve/reject body. */
    public record DecisionRequest(String reason) {}

    public record DecisionView(Long id, int stepIndex, String requiredPermission, String approverId,
                               String decision, boolean cfoOverride, String reason, Instant decidedAt) {}

    public record ApprovalRequestView(Long id, String requestType, String subjectRef, BigDecimal amount,
                                      String currency, String tierLabel, String status,
                                      List<String> stepPermissions, int requiredSteps, int currentStep,
                                      String requestedBy, Instant requestedAt, Instant decidedAt,
                                      String rejectReason, Long tenantId, List<DecisionView> decisions) {}

    /** Bridge for the RBAC APPROVAL constraint: is the latest request for an operation granted? */
    public record DecisionLookup(boolean approved, String status) {}
}
