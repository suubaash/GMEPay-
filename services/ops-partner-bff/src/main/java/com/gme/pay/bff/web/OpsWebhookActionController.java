package com.gme.pay.bff.web;

import com.gme.pay.bff.client.OperatorActionAuditClient;
import com.gme.pay.bff.client.WebhookOpsClient;
import com.gme.pay.rbac.RbacHeaders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Audited operator webhook-replay action.
 *
 * <p>{@code POST /v1/admin/webhooks/{id}/replay} — writes an operator-action audit
 * record then delegates to notification-webhook's delivery replay. RBAC-guarded (see
 * {@link OpsActionController#guard}).
 */
@RestController
@RequestMapping("/v1/admin/webhooks")
public class OpsWebhookActionController {

    private final WebhookOpsClient webhooks;
    private final OperatorActionAuditClient audit;

    public OpsWebhookActionController(WebhookOpsClient webhooks, OperatorActionAuditClient audit) {
        this.webhooks = webhooks;
        this.audit = audit;
    }

    @PostMapping("/{id}/replay")
    public WebhookOpsClient.ReplayResult replay(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, String> body,
            @RequestHeader(value = RbacHeaders.PRINCIPAL_ID, required = false) String principal,
            @RequestHeader(value = RbacHeaders.PERMISSIONS, required = false) String permissions) {
        OpsActionController.guard(permissions);
        String actor = OpsActionController.actor(principal);
        String reason = OpsActionController.reason(body);
        audit.record("webhook.replay", id, actor, reason);
        return webhooks.replay(id, actor);
    }
}
