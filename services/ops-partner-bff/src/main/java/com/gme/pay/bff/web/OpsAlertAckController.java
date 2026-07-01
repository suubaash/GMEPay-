package com.gme.pay.bff.web;

import com.gme.pay.bff.alert.OpsAlertStore;
import com.gme.pay.bff.alert.OpsAlertView;
import com.gme.pay.bff.client.OperatorActionAuditClient;
import com.gme.pay.rbac.RbacHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;

/**
 * Audited operator acknowledgement of an ops alert.
 *
 * <p>{@code POST /v1/admin/ops/alerts/{id}/ack} {operator, note} — marks the alert
 * acknowledged, which stops escalation and flips the alert from {@code open} to
 * {@code acked} in {@code GET /v1/admin/ops/alerts} and the control tower. {@code id} is the
 * alert's {@code seq}.
 *
 * <p><b>RBAC (fail closed)</b> — requires {@code ops:operate} via {@link OpsRbacGuard}
 * (403 when absent / lacking the permission), same as the other ops operator actions.
 *
 * <p><b>Audit (fail closed)</b> — writes a durable operator-action record
 * ({@code ops.alert.ack}) BEFORE mutating the alert; if the durable write fails the ack
 * FAILS (5xx) and nothing is mutated — no operator action without a durable audit record.
 */
@RestController
@RequestMapping("/v1/admin/ops/alerts")
public class OpsAlertAckController {

    private final OpsAlertStore store;
    private final OperatorActionAuditClient audit;
    private final OpsRbacGuard rbac;

    public OpsAlertAckController(OpsAlertStore store, OperatorActionAuditClient audit,
                                 OpsRbacGuard rbac) {
        this.store = store;
        this.audit = audit;
        this.rbac = rbac;
    }

    @PostMapping("/{id}/ack")
    public OpsAlertView ack(
            @PathVariable long id,
            @RequestBody(required = false) Map<String, String> body,
            @RequestHeader(value = RbacHeaders.PRINCIPAL_ID, required = false) String principal,
            @RequestHeader(value = RbacHeaders.PERMISSIONS, required = false) String permissions) {
        rbac.requireOps(permissions);
        String operator = firstNonBlank(str(body, "operator"), principal, "unknown");
        String note = str(body, "note");
        // Fail-closed durable audit BEFORE mutating.
        audit.recordDurable("ops.alert.ack", String.valueOf(id), operator, note);
        OpsAlertView.Ack ack = new OpsAlertView.Ack(operator, note, Instant.now().toString());
        return store.update(id, a -> a.withAck(ack))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "alert " + id + " not found (evicted or never seen)"));
    }

    private static String str(Map<String, String> body, String key) {
        return body == null ? null : body.get(key);
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "unknown";
    }
}
