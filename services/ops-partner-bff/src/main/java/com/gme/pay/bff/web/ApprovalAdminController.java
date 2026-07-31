package com.gme.pay.bff.web;

import com.gme.pay.bff.client.ApprovalQueueClient;
import com.gme.pay.bff.web.dto.ApprovalSummary;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
 * <p><b>Who is approving (T0-3).</b> The approver identity and permissions forwarded to
 * auth-identity — which enforces the per-step permission gate, maker-checker, and CFO
 * break-glass against them — now come from the <b>verified access token</b> via
 * {@link OpsRbacGuard#actor(String)} / {@link OpsRbacGuard#permissions()}, not from the
 * caller-supplied {@code X-Gme-Principal-Id} / {@code X-Gme-Permissions} request headers.
 * Forwarding client headers made maker-checker self-serve: a single caller could approve
 * their own request twice under two invented principal ids, or claim
 * {@code approval.cfo_override} outright.
 */
@RestController
@RequestMapping("/v1/admin/approvals")
public class ApprovalAdminController {

    private final ApprovalQueueClient approvals;
    private final OpsRbacGuard rbac;

    public ApprovalAdminController(ApprovalQueueClient approvals, OpsRbacGuard rbac) {
        this.approvals = approvals;
        this.rbac = rbac;
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
    public ApprovalSummary approve(@PathVariable Long id, @RequestBody(required = false) DecisionRequest body) {
        return approvals.approve(id, rbac.actor(null), rbac.permissions(), reason(body));
    }

    @PostMapping("/{id}/reject")
    public ApprovalSummary reject(@PathVariable Long id, @RequestBody(required = false) DecisionRequest body) {
        return approvals.reject(id, rbac.actor(null), rbac.permissions(), reason(body));
    }

    public record DecisionRequest(String reason) {}

    private static String reason(DecisionRequest body) {
        return body == null ? null : body.reason();
    }
}
