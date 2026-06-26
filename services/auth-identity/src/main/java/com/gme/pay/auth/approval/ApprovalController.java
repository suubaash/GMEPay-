package com.gme.pay.auth.approval;

import com.gme.pay.auth.approval.ApprovalDtos.ApprovalRequestView;
import com.gme.pay.auth.approval.ApprovalDtos.CreateApprovalRequest;
import com.gme.pay.auth.approval.ApprovalDtos.DecisionLookup;
import com.gme.pay.auth.approval.ApprovalDtos.DecisionRequest;
import com.gme.pay.auth.approval.ApprovalDtos.RequestApprovalCommand;
import com.gme.pay.rbac.RbacHeaders;
import com.gme.pay.rbac.RequiresPermission;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Approval-workflow API. The approver's identity + effective permissions are read from the
 * {@link RbacHeaders} (never from the request body, so an approver cannot self-assert their authority
 * in the payload).
 *
 * <p><b>Perimeter (#90).</b> auth-identity is NOT fronted by the api-gateway (the ops BFF calls these
 * routes server-to-server), so nothing strips/signs these headers at an edge. The gate that stops a
 * direct network attacker from forging {@code X-Gme-Permissions: approval.approve} is the
 * service-to-service internal-auth filter ({@code gmepay.internal-auth}): only the ops BFF / gateway
 * hold the shared {@code X-Gme-Internal} token. The forwarded approver permissions are therefore only
 * as trustworthy as the BFF's own (upstream) operator-authority enforcement — which is still being
 * wired (gated on the operator-auth story); until then this gate ensures only trusted internal callers
 * reach the endpoint, not that the named approver truly holds the permission.
 *
 * <ul>
 *   <li>{@code POST /v1/approvals} — open a request (auto-approved if below the tier threshold)</li>
 *   <li>{@code GET  /v1/approvals} — the pending queue (FIFO)</li>
 *   <li>{@code GET  /v1/approvals/{id}} — request detail + per-step decision trail</li>
 *   <li>{@code POST /v1/approvals/{id}/approve} — advance/finalise (gated on the step permission / CFO)</li>
 *   <li>{@code POST /v1/approvals/{id}/reject} — reject with a mandatory reason</li>
 *   <li>{@code GET  /v1/approvals/decision?type=&subjectRef=} — RBAC APPROVAL-constraint bridge</li>
 * </ul>
 */
@RestController
@RequestMapping("/v1/approvals")
public class ApprovalController {

    private final ApprovalWorkflowService approvals;

    public ApprovalController(ApprovalWorkflowService approvals) {
        this.approvals = approvals;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApprovalRequestView create(@RequestBody CreateApprovalRequest req,
                                      @RequestHeader(value = RbacHeaders.PRINCIPAL_ID, required = false) String principalId) {
        String requestedBy = requirePrincipal(principalId);
        return approvals.request(new RequestApprovalCommand(
                req.requestType(), req.subjectRef(), req.amount(), req.currency(), requestedBy, req.tenantId()));
    }

    @GetMapping
    @RequiresPermission("approval.view")
    public List<ApprovalRequestView> pending() {
        return approvals.listPending();
    }

    @GetMapping("/{id}")
    @RequiresPermission("approval.view")
    public ApprovalRequestView get(@PathVariable Long id) {
        return approvals.get(id);
    }

    @PostMapping("/{id}/approve")
    public ApprovalRequestView approve(@PathVariable Long id,
                                       @RequestBody(required = false) DecisionRequest body,
                                       @RequestHeader(value = RbacHeaders.PRINCIPAL_ID, required = false) String principalId,
                                       @RequestHeader(value = RbacHeaders.PERMISSIONS, required = false) String permissions) {
        return approvals.approve(id, requirePrincipal(principalId), csv(permissions),
                body == null ? null : body.reason());
    }

    @PostMapping("/{id}/reject")
    public ApprovalRequestView reject(@PathVariable Long id,
                                      @RequestBody(required = false) DecisionRequest body,
                                      @RequestHeader(value = RbacHeaders.PRINCIPAL_ID, required = false) String principalId,
                                      @RequestHeader(value = RbacHeaders.PERMISSIONS, required = false) String permissions) {
        return approvals.reject(id, requirePrincipal(principalId), csv(permissions),
                body == null ? null : body.reason());
    }

    @GetMapping("/decision")
    public DecisionLookup decision(@RequestParam String type, @RequestParam String subjectRef,
                                   @RequestHeader(value = RbacHeaders.TENANT_ID, required = false) Long tenantId) {
        return approvals.decision(type, subjectRef, tenantId);
    }

    // ---- helpers ----

    private static String requirePrincipal(String principalId) {
        if (principalId == null || principalId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "missing " + RbacHeaders.PRINCIPAL_ID + " (caller identity not stamped by the gateway)");
        }
        return principalId.trim();
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
