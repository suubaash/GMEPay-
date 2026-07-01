package com.gme.pay.bff.client.stub;

import com.gme.pay.bff.client.WebhookOpsClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Phase-1 in-memory stub of {@link WebhookOpsClient}. Returns a small deterministic
 * backlog (2 PENDING + 1 DLQ) so the control-tower webhook section renders a non-zero
 * gauge, and echoes a successful replay for any delivery id.
 *
 * <p>Default bean: wired unless {@code gmepay.webhook-ops.client=rest} selects the
 * live {@link com.gme.pay.bff.client.rest.RestWebhookOpsClient}.
 */
@Component
@ConditionalOnProperty(
        name = "gmepay.webhook-ops.client",
        havingValue = "stub",
        matchIfMissing = true)
public class StubWebhookOpsClient implements WebhookOpsClient {

    @Override
    public WebhookBacklog backlog() {
        return new WebhookBacklog(2, 1);
    }

    @Override
    public ReplayResult replay(String deliveryId, String actor) {
        return new ReplayResult(deliveryId, "REQUEUED", "replay accepted (stub)");
    }
}
