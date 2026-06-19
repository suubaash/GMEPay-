package com.gme.pay.bff.client;

import com.gme.pay.bff.web.dto.ApprovalSummary;
import java.util.List;
import java.util.Set;

/**
 * BFF adapter onto auth-identity's approval-workflow API ({@code /v1/approvals}), for the
 * Admin-UI approval queue ({@code /v1/admin/approvals}).
 *
 * <p>Two implementations, mutually exclusive via {@code gmepay.auth-identity.client}:
 * {@link com.gme.pay.bff.client.rest.RestApprovalQueueClient} (live HTTP, {@code =rest}) and
 * {@link com.gme.pay.bff.client.stub.StubApprovalQueueClient} (in-memory, default).
 *
 * <p>approve/reject forward the acting operator's identity + permissions ({@code approverId} /
 * {@code approverPermissions}, taken from the gateway-stamped headers on the inbound BFF request)
 * to auth-identity, which enforces the per-step permission gate + maker-checker.
 */
public interface ApprovalQueueClient {

    List<ApprovalSummary> listPending();

    ApprovalSummary get(Long id);

    ApprovalSummary approve(Long id, String approverId, Set<String> approverPermissions, String reason);

    ApprovalSummary reject(Long id, String approverId, Set<String> approverPermissions, String reason);
}
