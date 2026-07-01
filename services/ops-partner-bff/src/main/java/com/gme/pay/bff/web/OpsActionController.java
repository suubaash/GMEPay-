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

import java.util.Arrays;
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
 * <h2>RBAC</h2>
 * <p>The platform forwards the caller's permissions in {@code X-Gme-Permissions}
 * ({@link RbacHeaders#PERMISSIONS}). When that header is present it MUST contain the ops
 * operate permission ({@value #OPS_PERMISSION}) or the action is rejected 403. When the
 * header is absent the call is allowed (local dev / gate-off), matching the internal-auth
 * convention used elsewhere in the BFF.
 */
@RestController
@RequestMapping("/v1/admin")
public class OpsActionController {

    /** Permission required to invoke an ops operator action when RBAC headers are present. */
    static final String OPS_PERMISSION = "ops:operate";

    private final OpsControlClient opsControl;
    private final SettlementClient settlements;
    private final OperatorActionAuditClient audit;

    // Webhook + transaction clients are shared into the sibling action controllers via
    // this controller's constructor injection graph; kept here nullable-free by dedicated
    // controllers below. This controller owns the config-registry + settlement actions.
    public OpsActionController(OpsControlClient opsControl,
                               SettlementClient settlements,
                               OperatorActionAuditClient audit) {
        this.opsControl = opsControl;
        this.settlements = settlements;
        this.audit = audit;
    }

    @PostMapping("/ops/pause")
    public OperationalStatusView pause(@RequestBody(required = false) Map<String, String> body,
                                       @RequestHeader(value = RbacHeaders.PRINCIPAL_ID, required = false) String principal,
                                       @RequestHeader(value = RbacHeaders.PERMISSIONS, required = false) String permissions) {
        guard(permissions);
        String actor = actor(principal);
        String reason = reason(body);
        audit.record("ops.pause", "system", actor, reason);
        return opsControl.pause(actor, reason);
    }

    @PostMapping("/ops/resume")
    public OperationalStatusView resume(@RequestBody(required = false) Map<String, String> body,
                                        @RequestHeader(value = RbacHeaders.PRINCIPAL_ID, required = false) String principal,
                                        @RequestHeader(value = RbacHeaders.PERMISSIONS, required = false) String permissions) {
        guard(permissions);
        String actor = actor(principal);
        audit.record("ops.resume", "system", actor, reason(body));
        return opsControl.resume(actor);
    }

    @PostMapping("/ops/maintenance")
    public OperationalStatusView maintenance(@RequestBody(required = false) Map<String, String> body,
                                             @RequestHeader(value = RbacHeaders.PRINCIPAL_ID, required = false) String principal,
                                             @RequestHeader(value = RbacHeaders.PERMISSIONS, required = false) String permissions) {
        guard(permissions);
        String actor = actor(principal);
        String reason = reason(body);
        audit.record("ops.maintenance", "system", actor, reason);
        return opsControl.maintenance(actor, reason);
    }

    @PostMapping("/ops/suspend")
    public OperationalStatusView suspend(@RequestBody(required = false) Map<String, String> body,
                                         @RequestHeader(value = RbacHeaders.PRINCIPAL_ID, required = false) String principal,
                                         @RequestHeader(value = RbacHeaders.PERMISSIONS, required = false) String permissions) {
        guard(permissions);
        String actor = actor(principal);
        String scope = str(body, "scope");
        String ref = str(body, "ref");
        String reason = reason(body);
        if (ref == null || ref.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ref is required");
        }
        audit.record("ops.suspend", (scope == null ? "" : scope + ":") + ref, actor, reason);
        return opsControl.suspend(scope, ref, actor, reason);
    }

    @PostMapping("/ops/unsuspend")
    public OperationalStatusView unsuspend(@RequestBody(required = false) Map<String, String> body,
                                           @RequestHeader(value = RbacHeaders.PRINCIPAL_ID, required = false) String principal,
                                           @RequestHeader(value = RbacHeaders.PERMISSIONS, required = false) String permissions) {
        guard(permissions);
        String actor = actor(principal);
        String scope = str(body, "scope");
        String ref = str(body, "ref");
        if (ref == null || ref.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ref is required");
        }
        audit.record("ops.unsuspend", (scope == null ? "" : scope + ":") + ref, actor, reason(body));
        return opsControl.unsuspend(scope, ref, actor);
    }

    @PostMapping("/settlements/recon/rerun")
    public SettlementClient.ReconRerunResult reconRerun(@RequestBody(required = false) Map<String, String> body,
                                                        @RequestHeader(value = RbacHeaders.PRINCIPAL_ID, required = false) String principal,
                                                        @RequestHeader(value = RbacHeaders.PERMISSIONS, required = false) String permissions) {
        guard(permissions);
        String actor = actor(principal);
        String date = str(body, "date");
        String reason = reason(body);
        audit.record("settlement.recon.rerun", date == null ? "system" : date, actor, reason);
        return settlements.rerunRecon(date, actor, reason);
    }

    // -------- shared helpers ----------------------------------------------------

    static void guard(String permissionsHeader) {
        if (permissionsHeader == null || permissionsHeader.isBlank()) {
            // No RBAC headers (local dev / gate off) — allow through.
            return;
        }
        boolean hasOps = Arrays.stream(permissionsHeader.split(","))
                .map(String::trim)
                .anyMatch(OPS_PERMISSION::equals);
        if (!hasOps) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "ops operator action requires the '" + OPS_PERMISSION + "' permission");
        }
    }

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
