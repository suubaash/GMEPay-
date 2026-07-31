package com.gme.pay.bff.client.stub;

import com.gme.pay.bff.client.WebhookOpsClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

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
        havingValue = "stub")
public class StubWebhookOpsClient implements WebhookOpsClient {

    private static final Logger log = LoggerFactory.getLogger(StubWebhookOpsClient.class);

    @Override
    public WebhookBacklog backlog() {
        return new WebhookBacklog(2, 1);
    }

    @Override
    public ReplayResult replay(String deliveryId, String actor) {
        return new ReplayResult(deliveryId, "REQUEUED", "replay accepted (stub)");
    }

    /**
     * No endpoints (gap T5-8). Webhook endpoints live in notification-webhook's
     * {@code webhook_endpoint} table and this stub has none, so it reports none: fabricating
     * rows here would tell an operator that a partner's webhooks are healthy when nothing was
     * checked, which is the exact failure mode T5-8 exists to remove.
     */
    @Override
    public List<EndpointSigningHealth> endpointSigningHealth(Long partnerId) {
        log.warn("gmepay.webhook-ops.client is not 'rest' — webhook endpoint signing health "
                + "reports NO endpoints because notification-webhook is not being called. Set "
                + "GMEPAY_WEBHOOK_OPS_CLIENT=rest to see the real rows.");
        return List.of();
    }

    /**
     * Refuses to rotate. A stub cannot mint a secret notification-webhook's dispatcher would
     * reproduce, so a fake success would hand the operator a value to give a partner that could
     * never verify a signature — worse than the un-signable endpoint it claims to fix.
     */
    @Override
    public RotatedWebhookSecret rotateEndpointSecret(String endpointId, Long overlapMinutes) {
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "webhook secret rotation requires the live notification-webhook client "
                        + "(set gmepay.webhook-ops.client=rest); this instance would issue a "
                        + "secret the dispatcher could not reproduce");
    }
}
