package com.gme.pay.registry.web;

import com.gme.pay.contracts.ActivationGateView;
import com.gme.pay.contracts.LifecycleCommand;
import com.gme.pay.contracts.PartnerLifecycleAction;
import com.gme.pay.registry.lifecycle.PartnerLifecycleService;
import com.gme.pay.registry.partner.PartnerDraftService;
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
 * Slice 8 — operator-facing surface of the partner lifecycle FSM
 * ({@code docs/PARTNER_SETUP_PLAN.md} §"Slice 8", ADR-011). Every POST here is
 * 4-eyes gated (ADR-008) through the two-call protocol documented on
 * {@link PartnerLifecycleService}.
 *
 * <h2>Response shapes</h2>
 *
 * <ul>
 *   <li><b>202 Accepted</b> + {@code ChangeRequestView} — maker call; a
 *       change_request now awaits a second operator.</li>
 *   <li><b>200 OK</b> + {@code PartnerView} — checker call; the transition was
 *       applied, the view reflects the new status.</li>
 *   <li><b>422</b> + {@link ActivationGateView} — checker call on
 *       {@code /activate} when the activation gate fails; the body itemises
 *       the unmet pre-conditions. A plain-message 422 is also returned when
 *       the partner's CURRENT status does not permit the action (e.g.
 *       {@code /suspend} on a non-LIVE partner).</li>
 *   <li><b>409</b> — self-approval attempt (V005 4-eyes CHECK).</li>
 *   <li><b>400</b> — missing/unknown suspension reason, over-length notes,
 *       missing termination reason.</li>
 *   <li><b>404</b> — unknown partner code.</li>
 * </ul>
 */
@RestController
@RequestMapping("/v1/admin/partners/{partnerCode}/lifecycle")
public class PartnerLifecycleController {

    private final PartnerLifecycleService lifecycleService;

    public PartnerLifecycleController(PartnerLifecycleService lifecycleService) {
        this.lifecycleService = lifecycleService;
    }

    /**
     * Propose / approve {@code UAT → LIVE}. The activation gate must pass at
     * approval time; failure returns 422 + the unmet condition list and leaves
     * the change_request PROPOSED. On success {@code go_live_at} +
     * {@code activated_by} are stamped (first activation only) and a
     * {@code PARTNER_ACTIVATED} audit row is chained (ADR-007).
     */
    @PostMapping("/activate")
    public ResponseEntity<?> activate(
            @PathVariable String partnerCode,
            @RequestBody(required = false) LifecycleCommand.Activate body,
            @RequestHeader(value = "X-Actor", required = false) String actor) {
        return respond(lifecycleService.execute(
                partnerCode, PartnerLifecycleAction.ACTIVATE, null, null, actor));
    }

    /**
     * Propose / approve {@code LIVE → SUSPENDED}. {@code reason} must be a
     * {@code SuspensionReason} name (V025 CHECK roster); {@code notes} is
     * optional operator free text (≤500).
     */
    @PostMapping("/suspend")
    public ResponseEntity<?> suspend(
            @PathVariable String partnerCode,
            @RequestBody LifecycleCommand.Suspend body,
            @RequestHeader(value = "X-Actor", required = false) String actor) {
        String reason = body == null ? null : body.reason();
        String notes = body == null ? null : body.notes();
        return respond(lifecycleService.execute(
                partnerCode, PartnerLifecycleAction.SUSPEND, reason, notes, actor));
    }

    /**
     * Propose / approve {@code SUSPENDED → LIVE}. Clears suspension_reason,
     * suspension_notes and suspended_at. {@code go_live_at} is NOT re-stamped —
     * it marks the FIRST activation only.
     */
    @PostMapping("/reactivate")
    public ResponseEntity<?> reactivate(
            @PathVariable String partnerCode,
            @RequestBody(required = false) LifecycleCommand.Reactivate body,
            @RequestHeader(value = "X-Actor", required = false) String actor) {
        return respond(lifecycleService.execute(
                partnerCode, PartnerLifecycleAction.REACTIVATE, null, null, actor));
    }

    /**
     * Propose / approve {@code LIVE|SUSPENDED → TERMINATED} (terminal).
     * {@code reason} is required free text (≤500).
     */
    @PostMapping("/terminate")
    public ResponseEntity<?> terminate(
            @PathVariable String partnerCode,
            @RequestBody LifecycleCommand.Terminate body,
            @RequestHeader(value = "X-Actor", required = false) String actor) {
        String reason = body == null ? null : body.reason();
        return respond(lifecycleService.execute(
                partnerCode, PartnerLifecycleAction.TERMINATE, reason, null, actor));
    }

    /**
     * Non-mutating activation-gate evaluation, so the Admin UI can render the
     * pre-activation checklist BEFORE the operator proposes Go-live. Always
     * 200 — a failing gate is a normal answer here, not an error.
     */
    @GetMapping("/preconditions")
    public ActivationGateView preconditions(@PathVariable String partnerCode) {
        return lifecycleService.preconditions(partnerCode).toView();
    }

    /**
     * Map the service outcome onto the three HTTP shapes documented above.
     *
     * <p>Slice 8 Lane B: a Completed transition that PROVISIONED credentials
     * (first entry into SANDBOX / LIVE) returns
     * {@link com.gme.pay.contracts.PartnerActivationView} — the partner view
     * plus the ONE-TIME plaintext bundle (SEC-09 §4: shown once, never
     * recoverable; intermediaries must not log this body). Transitions that
     * issued nothing return the plain {@code PartnerView}.
     */
    private static ResponseEntity<?> respond(PartnerLifecycleService.Outcome outcome) {
        return switch (outcome) {
            case PartnerLifecycleService.Outcome.Pending p ->
                    ResponseEntity.status(HttpStatus.ACCEPTED)
                            .body(ChangeRequestController.toView(p.changeRequest()));
            case PartnerLifecycleService.Outcome.Completed c ->
                    c.issuedCredentials() == null
                            ? ResponseEntity.ok(PartnerDraftService.toView(c.partner()))
                            : ResponseEntity.ok(new com.gme.pay.contracts.PartnerActivationView(
                                    PartnerDraftService.toView(c.partner()),
                                    c.issuedCredentials()));
            case PartnerLifecycleService.Outcome.GateFailed g ->
                    ResponseEntity.unprocessableEntity().body(g.gate().toView());
        };
    }
}
