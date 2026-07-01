package com.gme.pay.bff.web;

import com.gme.pay.bff.client.OpsControlClient;
import com.gme.pay.bff.client.OperatorActionAuditClient;
import com.gme.pay.bff.client.SettlementClient;
import com.gme.pay.bff.client.WebhookOpsClient;
import com.gme.pay.contracts.OperationalStatusView;
import com.gme.pay.rbac.RbacHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Audited Ops operator-action endpoints — thin proxies that write an operator-action
 * audit record (who/what/when/reason) BEFORE delegating to the upstream service, so the
 * intent is durably recorded even if the delegation later fails.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code POST /v1/admin/ops/pause|resume|maintenance|suspend|unsuspend} → config-registry</li>
 *   <li>{@code POST /v1/admin/settlements/recon/rerun} → settlement-reconciliation</li>
 * </ul>
 * (transaction resolve + webhook replay live in {@link OpsTransactionActionController} /
 * {@link OpsWebhookActionController} under their own URL roots.)
 *
 * <h2>RBAC (fail closed)</h2>
 * <p>The platform forwards the caller's permissions in {@code X-Gme-Permissions}
 * ({@link RbacHeaders#PERMISSIONS}). Authorization is delegated to {@link OpsRbacGuard},
 * which DENIES (403) when the header is absent or lacks the ops operate permission —
 * no permission ⇒ no privileged action (config-overridable only via the dev flag
 * {@code gmepay.ops.rbac.enforce}, default = enforce).
 *
 * <h2>Audit (fail closed for money-affecting actions)</h2>
 * <p>Every action here is money/state-affecting, so the operator-action audit record is
 * written with {@link OperatorActionAuditClient#recordDurable} BEFORE delegating; if the
 * durable audit write fails the action FAILS (5xx) and the upstream is NOT called — no
 * money-affecting action without a durable audit record.
 */
@RestController
@RequestMapping("/v1/admin")
public class OpsActionController {

    private final OpsControlClient opsControl;
    private final SettlementClient settlements;
    private final OperatorActionAuditClient audit;
    private final OpsRbacGuard rbac;

    // Webhook + transaction clients are shared into the sibling action controllers via
    // this controller's constructor injection graph; kept here nullable-free by dedicated
    // controllers below. This controller owns the config-registry + settlement actions.
    public OpsActionController(OpsControlClient opsControl,
                               SettlementClient settlements,
                               OperatorActionAuditClient audit,
                               OpsRbacGuard rbac) {
        this.opsControl = opsControl;
        this.settlements = settlements;
        this.audit = audit;
        this.rbac = rbac;
    }

    @PostMapping("/ops/pause")
    public OperationalStatusView pause(@RequestBody(required = false) Map<String, String> body,
                                       @RequestHeader(value = RbacHeaders.PRINCIPAL_ID, required = false) String principal,
                                       @RequestHeader(value = RbacHeaders.PERMISSIONS, required = false) String permissions) {
        rbac.requireOps(permissions);
        String actor = actor(principal);
        String reason = reason(body);
        audit.recordDurable("ops.pause", "system", actor, reason);
        return opsControl.pause(actor, reason);
    }

    @PostMapping("/ops/resume")
    public OperationalStatusView resume(@RequestBody(required = false) Map<String, String> body,
                                        @RequestHeader(value = RbacHeaders.PRINCIPAL_ID, required = false) String principal,
                                        @RequestHeader(value = RbacHeaders.PERMISSIONS, required = false) String permissions) {
        rbac.requireOps(permissions);
        String actor = actor(principal);
        audit.recordDurable("ops.resume", "system", actor, reason(body));
        return opsControl.resume(actor);
    }

    @PostMapping("/ops/maintenance")
    public OperationalStatusView maintenance(@RequestBody(required = false) Map<String, String> body,
                                             @RequestHeader(value = RbacHeaders.PRINCIPAL_ID, required = false) String principal,
                                             @RequestHeader(value = RbacHeaders.PERMISSIONS, required = false) String permissions) {
        rbac.requireOps(permissions);
        String actor = actor(principal);
        String reason = reason(body);
        audit.recordDurable("ops.maintenance", "system", actor, reason);
        return opsControl.maintenance(actor, reason);
    }

    @PostMapping("/ops/suspend")
    public OperationalStatusView suspend(@RequestBody(required = false) Map<String, String> body,
                                         @RequestHeader(value = RbacHeaders.PRINCIPAL_ID, required = false) String principal,
                                         @RequestHeader(value = RbacHeaders.PERMISSIONS, required = false) String permissions) {
        rbac.requireOps(permissions);
        String actor = actor(principal);
        String scope = str(body, "scope");
        String ref = str(body, "ref");
        String reason = reason(body);
        if (ref == null || ref.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ref is required");
        }
        audit.recordDurable("ops.suspend", (scope == null ? "" : scope + ":") + ref, actor, reason);
        return opsControl.suspend(scope, ref, actor, reason);
    }

    @PostMapping("/ops/unsuspend")
    public OperationalStatusView unsuspend(@RequestBody(required = false) Map<String, String> body,
                                           @RequestHeader(value = RbacHeaders.PRINCIPAL_ID, required = false) String principal,
                                           @RequestHeader(value = RbacHeaders.PERMISSIONS, required = false) String permissions) {
        rbac.requireOps(permissions);
        String actor = actor(principal);
        String scope = str(body, "scope");
        String ref = str(body, "ref");
        if (ref == null || ref.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ref is required");
        }
        audit.recordDurable("ops.unsuspend", (scope == null ? "" : scope + ":") + ref, actor, reason(body));
        return opsControl.unsuspend(scope, ref, actor);
    }

    @PostMapping("/settlements/recon/rerun")
    public SettlementClient.ReconRerunResult reconRerun(@RequestBody(required = false) Map<String, String> body,
                                                        @RequestHeader(value = RbacHeaders.PRINCIPAL_ID, required = false) String principal,
                                                        @RequestHeader(value = RbacHeaders.PERMISSIONS, required = false) String permissions) {
        rbac.requireOps(permissions);
        String actor = actor(principal);
        String date = str(body, "date");
        String reason = reason(body);
        audit.recordDurable("settlement.recon.rerun", date == null ? "system" : date, actor, reason);
        return settlements.rerunRecon(date, actor, reason);
    }

    // -------- shared helpers ----------------------------------------------------

    static String actor(String principal) {
        return principal == null || principal.isBlank() ? "unknown" : principal;
    }

    static String reason(Map<String, String> body) {
        return str(body, "reason");
    }

    static String str(Map<String, String> body, String key) {
        return body == null ? null : body.get(key);
    }
}
