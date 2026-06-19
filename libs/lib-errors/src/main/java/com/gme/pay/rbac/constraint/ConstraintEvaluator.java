package com.gme.pay.rbac.constraint;

import java.util.Optional;

/** Evaluates one {@link ConstraintType}. Returns a violation reason, or empty if satisfied. */
public interface ConstraintEvaluator {

    ConstraintType type();

    Optional<String> violation(Constraint constraint, ConstraintContext ctx);
}
