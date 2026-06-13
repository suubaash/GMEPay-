package com.gme.pay.contracts;

import java.util.List;

/**
 * Result of the Slice 8 activation pre-condition gate, served by
 * {@code GET /v1/admin/partners/{partnerCode}/lifecycle/preconditions} and
 * returned as the 422 body when an {@code ACTIVATE} approval fails the gate.
 *
 * <p>The gate is a pure read — evaluating it never mutates the partner. The
 * Admin UI renders {@link #unmet()} as a pre-activation checklist so operators
 * see exactly which conditions still block Go-live.
 *
 * @param passes {@code true} iff every pre-condition is satisfied and the
 *               {@code UAT → LIVE} transition may be applied.
 * @param unmet  the unmet conditions, empty when {@link #passes()} is true.
 */
public record ActivationGateView(boolean passes, List<UnmetConditionView> unmet) {
}
