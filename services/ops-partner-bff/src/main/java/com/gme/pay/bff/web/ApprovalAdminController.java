package com.gme.pay.bff.web;

import com.gme.pay.bff.client.ApprovalQueueClient;
import com.gme.pay.bff.web.dto.ApprovalSummary;
import com.gme.pay.rbac.RbacHeaders;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin approval-queue BFF endpoints (admin-ui {@code /approvals} → auth-identity {@code /v1/approvals}).
 *
 * <ul>
 *   <li>{@code GET  /v1/admin/approvals} — pending queue ({@link ApprovalSummary}[])</li>
 *   <li>{@code GET  /v1/admin/approvals/{id}} — request detail + decision trail</li>
 *   <li>{@code POST /v1/admin/approvals/{id}/approve} — advance/finalise</li>
 *   <li>{@code POST /v1/admin/approvals/{id}/reject} — reject with a reason</li>
 * </ul>
 *
 * <p>The acting operator's identity + permissions ride on the gateway-stamped {@link RbacHeaders}
 * of the inbound request; the controller forwards them so auth-identity enforces the per-step
 * permission gate, maker-checker, and CFO break-glass against the real approver.
 */
@RestController
@RequestMapping("/v1/admin/approvals")
public class ApprovalAdminController {

    private final ApprovalQueueClient approvals;

    public ApprovalAdminController(ApprovalQueueClient approvals) {
        this.approvals = approvals;
    }

    @GetMapping
    public List<ApprovalSummary> pending() {
        return approvals.listPending();
    }

    @GetMapping("/{id}")
    public ApprovalSummary get(@PathVariable Long id) {
        return approvals.get(id);
    }

    @PostMapping("/{id}/approve")
    public ApprovalSummary approve(@PathVariable Long id, @RequestBody(required = false) DecisionRequest body,
                                   @RequestHeader(value = RbacHeaders.PRINCIPAL_ID, required = false) String principalId,
                                   @RequestHeader(value = RbacHeaders.PERMISSIONS, required = false) String permissions) {
        return approvals.approve(id, principalId, csv(permissions), reason(body));
    }

    @PostMapping("/{id}/reject")
    public ApprovalSummary reject(@PathVariable Long id, @RequestBody(required = false) DecisionRequest body,
                                  @RequestHeader(value = RbacHeaders.PRINCIPAL_ID, required = false) String principalId,
                                  @RequestHeader(value = RbacHeaders.PERMISSIONS, required = false) String permissions) {
        return approvals.reject(id, principalId, csv(permissions), reason(body));
    }

    public record DecisionRequest(String reason) {}

    private static String reason(DecisionRequest body) {
        return body == null ? null : body.reason();
    }

    private static Set<String> csv(String header) {
        if (header == null || header.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(header.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
