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
 * <p>{@code POST /v1/admin/webhooks/{id}/replay} — durably writes an operator-action
 * audit record (fail-closed) then delegates to notification-webhook's delivery replay.
 * Fail-closed RBAC via {@link OpsRbacGuard}.
 */
@RestController
@RequestMapping("/v1/admin/webhooks")
public class OpsWebhookActionController {

    private final WebhookOpsClient webhooks;
    private final OperatorActionAuditClient audit;
    private final OpsRbacGuard rbac;

    public OpsWebhookActionController(WebhookOpsClient webhooks, OperatorActionAuditClient audit,
                                      OpsRbacGuard rbac) {
        this.webhooks = webhooks;
        this.audit = audit;
        this.rbac = rbac;
    }

    @PostMapping("/{id}/replay")
    public WebhookOpsClient.ReplayResult replay(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, String> body,
            @RequestHeader(value = RbacHeaders.PRINCIPAL_ID, required = false) String principal,
            @RequestHeader(value = RbacHeaders.PERMISSIONS, required = false) String permissions) {
        rbac.requireOps(permissions);
        String actor = OpsActionController.actor(principal);
        String reason = OpsActionController.reason(body);
        audit.recordDurable("webhook.replay", id, actor, reason);
        return webhooks.replay(id, actor);
    }
}
