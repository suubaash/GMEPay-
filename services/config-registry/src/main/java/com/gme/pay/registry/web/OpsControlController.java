package com.gme.pay.registry.web;

import com.gme.pay.contracts.OperationalStatusView;
import com.gme.pay.registry.ops.OpsControlService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator-facing surface of the Operations kill-switch (global pause /
 * maintenance mode + emergency entity suspend/quarantine).
 *
 * <h2>Read</h2>
 * <pre>GET /v1/ops/operational-status → {@link OperationalStatusView}</pre>
 *
 * <h2>Actions (single-operator, immediate — every one hash-chain audited)</h2>
 * <ul>
 *   <li>{@code POST /v1/ops/pause}       {reason}</li>
 *   <li>{@code POST /v1/ops/resume}</li>
 *   <li>{@code POST /v1/ops/maintenance} {on, reason}</li>
 *   <li>{@code POST /v1/ops/suspend}     {entityType, entityId, reason}</li>
 *   <li>{@code POST /v1/ops/unsuspend}   {entityType, entityId}</li>
 * </ul>
 *
 * <p>All actions are idempotent and return the resulting {@link OperationalStatusView}.
 *
 * <h2>Security note</h2>
 *
 * <p>Like {@link AuditLogController}, this service endpoint is intended to be
 * reached only through the BFF, which adds the Keycloak OIDC ops-role gate at the
 * edge (config-registry itself carries no in-process RBAC — none exists in this
 * service). The operator identity arrives as the {@code X-Actor} header (same
 * convention as {@link PartnerLifecycleController}) and is recorded on every audit
 * row; {@code X-Forwarded-For} carries the client IP for the same row.
 */
@RestController
@RequestMapping("/v1/ops")
public class OpsControlController {

    private final OpsControlService service;

    public OpsControlController(OpsControlService service) {
        this.service = service;
    }

    @GetMapping("/operational-status")
    public OperationalStatusView status() {
        return service.status();
    }

    @PostMapping("/pause")
    public OperationalStatusView pause(
            @RequestBody(required = false) PauseRequest body,
            @RequestHeader(value = "X-Actor", required = false) String actor,
            @RequestHeader(value = "X-Forwarded-For", required = false) String ip) {
        return service.pause(body == null ? null : body.reason(), actor, ip);
    }

    @PostMapping("/resume")
    public OperationalStatusView resume(
            @RequestHeader(value = "X-Actor", required = false) String actor,
            @RequestHeader(value = "X-Forwarded-For", required = false) String ip) {
        return service.resume(actor, ip);
    }

    @PostMapping("/maintenance")
    public OperationalStatusView maintenance(
            @RequestBody MaintenanceRequest body,
            @RequestHeader(value = "X-Actor", required = false) String actor,
            @RequestHeader(value = "X-Forwarded-For", required = false) String ip) {
        boolean on = body != null && body.on();
        String reason = body == null ? null : body.reason();
        return service.maintenance(on, reason, actor, ip);
    }

    @PostMapping("/suspend")
    public OperationalStatusView suspend(
            @RequestBody SuspendRequest body,
            @RequestHeader(value = "X-Actor", required = false) String actor,
            @RequestHeader(value = "X-Forwarded-For", required = false) String ip) {
        String type = body == null ? null : body.entityType();
        String id = body == null ? null : body.entityId();
        String reason = body == null ? null : body.reason();
        return service.suspend(type, id, reason, actor, ip);
    }

    @PostMapping("/unsuspend")
    public OperationalStatusView unsuspend(
            @RequestBody SuspendRequest body,
            @RequestHeader(value = "X-Actor", required = false) String actor,
            @RequestHeader(value = "X-Forwarded-For", required = false) String ip) {
        String type = body == null ? null : body.entityType();
        String id = body == null ? null : body.entityId();
        return service.unsuspend(type, id, actor, ip);
    }

    // ---- Request bodies ----------------------------------------------------

    /** {@code {"reason": "..."}} — reason optional. */
    public record PauseRequest(String reason) {
    }

    /** {@code {"on": true, "reason": "..."}} — enter/exit maintenance mode. */
    public record MaintenanceRequest(boolean on, String reason) {
    }

    /** {@code {"entityType": "PARTNER", "entityId": "GMEREMIT", "reason": "..."}}. */
    public record SuspendRequest(String entityType, String entityId, String reason) {
    }
}
