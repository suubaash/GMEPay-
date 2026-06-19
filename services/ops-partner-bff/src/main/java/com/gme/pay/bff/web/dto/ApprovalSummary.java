package com.gme.pay.bff.web.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * An approval request as the Admin-UI approval queue consumes it ({@code /v1/admin/approvals}).
 * Maps from auth-identity's {@code ApprovalRequestView}. Timestamps are relayed as ISO strings
 * (no re-parsing in the BFF). {@code decisions} is the per-step approver trail.
 */
public record ApprovalSummary(Long id, String requestType, String subjectRef, BigDecimal amount,
                              String currency, String tierLabel, String status, int requiredSteps,
                              int currentStep, String requestedBy, String requestedAt, String decidedAt,
                              String rejectReason, List<Decision> decisions) {

    public record Decision(int stepIndex, String requiredPermission, String approverId, String decision,
                           boolean cfoOverride, String reason, String decidedAt) {}
}
