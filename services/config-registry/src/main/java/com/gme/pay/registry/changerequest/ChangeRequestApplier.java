package com.gme.pay.registry.changerequest;

import com.gme.pay.changerequest.ChangeRequest;

/**
 * SPI implemented per aggregate kind to actually mutate the aggregate when a
 * change_request reaches {@link com.gme.pay.changerequest.ChangeRequestState#APPLIED}.
 *
 * <p>The {@link ChangeRequestService} is the sole gatekeeper that walks a
 * change_request through the FSM (DRAFT → PROPOSED → APPROVED → APPLIED) and
 * invokes the matching applier on the APPLY step. This is the design
 * commitment from PARTNER_SETUP_PLAN.md §Slice 1 ¶1B.2:
 *
 * <blockquote>
 *   {@code ChangeRequestService.apply} MUST be the only path that mutates the
 *   underlying aggregate (Slice 1 only wires this for partner; later slices wire
 *   more aggregates).
 * </blockquote>
 *
 * <h2>Contract</h2>
 *
 * <p>An applier is keyed by {@link #aggregateType()} and the service routes to
 * it by exact-string match on {@link ChangeRequest#aggregateType()}. Multiple
 * appliers can coexist in the same Spring context — Slice 1 wires only the
 * partner applier; later slices add bank-account, fee-schedule, etc.
 *
 * <p>The apply method runs inside the same transaction as the change_request
 * UPDATE that flips state to APPLIED, so an exception thrown here rolls back
 * the state transition as well as the aggregate mutation. This keeps audit_log
 * (ADR-007) consistent: nothing is written unless everything is written.
 */
public interface ChangeRequestApplier {

    /**
     * Aggregate type this applier handles. Must match
     * {@link ChangeRequest#aggregateType()} exactly. Examples: {@code "partner"},
     * {@code "partner_bank_account"}, {@code "rule"}.
     */
    String aggregateType();

    /**
     * Apply the proposed change to the underlying aggregate. Called by
     * {@link ChangeRequestService#apply} inside an existing transaction; throw
     * to abort the apply (and thereby the state transition to APPLIED).
     *
     * @param request the change_request being applied (carries the JSONB payload,
     *                the field set the change is allowed to touch, and the
     *                aggregate key)
     */
    void apply(ChangeRequest request);
}
