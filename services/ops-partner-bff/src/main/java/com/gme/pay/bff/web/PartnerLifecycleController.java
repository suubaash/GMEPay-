package com.gme.pay.bff.web;

import com.gme.pay.bff.client.ConfigRegistryClient;
import com.gme.pay.bff.web.dto.LifecycleActionRequest;
import com.gme.pay.contracts.ActivationGateView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Slice 8 Lane A — partner lifecycle pass-throughs for the Admin UI.
 *
 * <p>Pure pass-throughs: each call delegates to {@link ConfigRegistryClient},
 * which adapts to config-registry's
 * {@code POST /v1/admin/partners/{code}/lifecycle/{action}} and
 * {@code GET /v1/admin/partners/{code}/lifecycle/preconditions} endpoints.
 * Upstream 202/200/422/409/400/404 pass through with their messages preserved.
 *
 * <p>SECURITY: the {@code /lifecycle/activate} response MAY carry an
 * {@link com.gme.pay.contracts.IssuedCredentialBundle} inside a
 * {@link com.gme.pay.contracts.PartnerActivationView}. The
 * {@code IssuedCredentialBundleLogMaskingFilter} ensures those bodies are
 * NEVER written to any access or body log (SEC-09 §4).
 */
@RestController
@RequestMapping("/v1/admin/partners/{partnerCode}/lifecycle")
public class PartnerLifecycleController {

    private final ConfigRegistryClient configRegistry;

    public PartnerLifecycleController(ConfigRegistryClient configRegistry) {
        this.configRegistry = configRegistry;
    }

    /**
     * Propose / approve {@code UAT → LIVE}. A 202 Accepted body is the
     * pending ChangeRequestView (maker call); a 200 OK body is a
     * PartnerView or PartnerActivationView (checker call, possibly carrying
     * the ONE-TIME credential bundle). 422 when the activation gate fails.
     */
    @PostMapping("/activate")
    public ResponseEntity<?> activate(
            @PathVariable String partnerCode,
            @RequestBody(required = false) LifecycleActionRequest body,
            @RequestHeader(value = "X-Actor", required = false) String actor) {
        return configRegistry.lifecycleActivate(partnerCode, actor);
    }

    /**
     * Propose / approve {@code LIVE → SUSPENDED}. {@code reason} must be a
     * SuspensionReason name (V025 CHECK roster); {@code notes} optional (≤500).
     */
    @PostMapping("/suspend")
    public ResponseEntity<?> suspend(
            @PathVariable String partnerCode,
            @RequestBody LifecycleActionRequest body,
            @RequestHeader(value = "X-Actor", required = false) String actor) {
        String reason = body == null ? null : body.reason();
        String notes = body == null ? null : body.notes();
        return configRegistry.lifecycleSuspend(partnerCode, reason, notes, actor);
    }

    /**
     * Propose / approve {@code SUSPENDED → LIVE}. Clears suspension fields.
     */
    @PostMapping("/reactivate")
    public ResponseEntity<?> reactivate(
            @PathVariable String partnerCode,
            @RequestBody(required = false) LifecycleActionRequest body,
            @RequestHeader(value = "X-Actor", required = false) String actor) {
        return configRegistry.lifecycleReactivate(partnerCode, actor);
    }

    /**
     * Propose / approve {@code LIVE|SUSPENDED → TERMINATED} (terminal).
     * {@code reason} required free text ≤500 chars.
     */
    @PostMapping("/terminate")
    public ResponseEntity<?> terminate(
            @PathVariable String partnerCode,
            @RequestBody LifecycleActionRequest body,
            @RequestHeader(value = "X-Actor", required = false) String actor) {
        String reason = body == null ? null : body.reason();
        return configRegistry.lifecycleTerminate(partnerCode, reason, actor);
    }

    /**
     * Non-mutating activation-gate evaluation. Always 200 — a failing gate
     * is a normal answer, not an error. Powers the Admin UI pre-activation
     * checklist.
     */
    @GetMapping("/preconditions")
    public ActivationGateView preconditions(@PathVariable String partnerCode) {
        return configRegistry.lifecyclePreconditions(partnerCode);
    }
}
