package com.gme.pay.bff.client.stub;

import com.gme.pay.bff.client.ApprovalQueueClient;
import com.gme.pay.bff.web.dto.ApprovalSummary;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * In-memory {@link ApprovalQueueClient} so the BFF + approval queue boot standalone without
 * auth-identity. Wired by default; {@link com.gme.pay.bff.client.rest.RestApprovalQueueClient}
 * takes over when {@code gmepay.auth-identity.client=rest}. Stateless demo data mirroring the
 * V005 refund tiers; mutations echo a decided shape back.
 */
@Component
@ConditionalOnProperty(name = "gmepay.auth-identity.client", havingValue = "stub", matchIfMissing = true)
public class StubApprovalQueueClient implements ApprovalQueueClient {

    private static ApprovalSummary pending(long id, String ref, String amount, String tier, int steps, int cur) {
        return new ApprovalSummary(id, "REFUND", ref, new BigDecimal(amount), "USD", tier, "PENDING",
                steps, cur, "op.maria", "2026-06-17T00:00:00Z", null, null, List.of());
    }

    @Override
    public List<ApprovalSummary> listPending() {
        return List.of(
                pending(1001, "RF-2025-0420", "2500.00", "L1", 1, 0),
                pending(1002, "RF-2025-0421", "9000.00", "L2_CFO", 2, 1));
    }

    @Override
    public ApprovalSummary get(Long id) {
        return pending(id, "RF-2025-0420", "2500.00", "L1", 1, 0);
    }

    @Override
    public ApprovalSummary approve(Long id, String approverId, Set<String> approverPermissions, String reason) {
        return new ApprovalSummary(id, "REFUND", "RF-2025-0420", new BigDecimal("2500.00"), "USD", "L1",
                "APPROVED", 1, 1, "op.maria", "2026-06-17T00:00:00Z", "2026-06-17T01:00:00Z", null,
                List.of(new ApprovalSummary.Decision(0, "refund.approve_l1", approverId, "APPROVE", false, reason,
                        "2026-06-17T01:00:00Z")));
    }

    @Override
    public ApprovalSummary reject(Long id, String approverId, Set<String> approverPermissions, String reason) {
        return new ApprovalSummary(id, "REFUND", "RF-2025-0420", new BigDecimal("2500.00"), "USD", "L1",
                "REJECTED", 1, 0, "op.maria", "2026-06-17T00:00:00Z", "2026-06-17T01:00:00Z", reason,
                List.of(new ApprovalSummary.Decision(0, null, approverId, "REJECT", false, reason,
                        "2026-06-17T01:00:00Z")));
    }
}
