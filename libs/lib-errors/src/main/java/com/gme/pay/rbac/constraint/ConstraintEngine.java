package com.gme.pay.rbac.constraint;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Evaluates a set of constraints against a {@link ConstraintContext} with <b>cascading AND</b>
 * semantics — every constraint must pass for access to be allowed; all failures are collected
 * so the caller sees exactly what blocked them (e.g. "location AND time AND amount").
 *
 * <p>A {@link ConstraintContext#superuser()} (CFO / break-glass) bypasses all constraints.
 * Stateless and thread-safe; construct once and reuse.
 */
public final class ConstraintEngine {

    private final Map<ConstraintType, ConstraintEvaluator> evaluators = new EnumMap<>(ConstraintType.class);

    /** Default engine with the built-in TIME / LOCATION / AMOUNT / DATA_FILTER / APPROVAL evaluators. */
    public ConstraintEngine() {
        this(List.of(
                new TimeWindowConstraintEvaluator(),
                new LocationConstraintEvaluator(),
                new AmountThresholdConstraintEvaluator(),
                new DataFilterConstraintEvaluator(),
                new ApprovalConstraintEvaluator()));
    }

    public ConstraintEngine(List<ConstraintEvaluator> evaluators) {
        for (ConstraintEvaluator e : evaluators) {
            this.evaluators.put(e.type(), e);
        }
    }

    public ConstraintDecision evaluate(List<Constraint> constraints, ConstraintContext ctx) {
        if (ctx.superuser()) {
            return ConstraintDecision.allow(); // CFO / break-glass override
        }
        if (constraints == null || constraints.isEmpty()) {
            return ConstraintDecision.allow();
        }
        List<String> violations = new ArrayList<>();
        for (Constraint c : constraints) {
            ConstraintEvaluator ev = evaluators.get(c.type());
            if (ev == null) {
                // Unknown/forward-compat type: fail closed — an unrecognised constraint must
                // not silently grant access.
                violations.add("unsupported constraint type: " + c.type());
                continue;
            }
            ev.violation(c, ctx).ifPresent(violations::add);
        }
        return violations.isEmpty() ? ConstraintDecision.allow() : ConstraintDecision.deny(violations);
    }
}
