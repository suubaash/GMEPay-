package com.gme.pay.rbac.constraint;

import java.util.List;

/**
 * Outcome of evaluating a set of constraints (cascading AND). {@code allowed} is true only
 * when every constraint passed; {@code violations} lists each failure reason.
 */
public record ConstraintDecision(boolean allowed, List<String> violations) {

    public ConstraintDecision {
        violations = violations == null ? List.of() : List.copyOf(violations);
    }

    public static ConstraintDecision allow() {
        return new ConstraintDecision(true, List.of());
    }

    public static ConstraintDecision deny(List<String> violations) {
        return new ConstraintDecision(false, violations);
    }

    /** Joined reason for audit / the 403 body. */
    public String reason() {
        return allowed ? "ok" : String.join("; ", violations);
    }
}
