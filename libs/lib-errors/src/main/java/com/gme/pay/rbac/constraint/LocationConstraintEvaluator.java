package com.gme.pay.rbac.constraint;

import java.util.Optional;
import java.util.Set;

/**
 * LOCATION constraint: the caller's country / region / office must be in the respective
 * allowlist. An empty allowlist for a dimension means "no restriction on that dimension".
 * Config: {@code countries}, {@code regions}, {@code offices} (each CSV).
 *
 * <p>Example (Japan report): {@code countries=JP} (or {@code regions=JAPAN}).
 */
public class LocationConstraintEvaluator implements ConstraintEvaluator {

    @Override
    public ConstraintType type() {
        return ConstraintType.LOCATION;
    }

    @Override
    public Optional<String> violation(Constraint c, ConstraintContext ctx) {
        Optional<String> v = check("country", c.getSet("countries"), ctx.country());
        if (v.isPresent()) return v;
        v = check("region", c.getSet("regions"), ctx.region());
        if (v.isPresent()) return v;
        return check("office", c.getSet("offices"), ctx.office());
    }

    private static Optional<String> check(String dim, Set<String> allow, String actual) {
        if (allow.isEmpty()) {
            return Optional.empty(); // no restriction on this dimension
        }
        if (actual == null || !allow.contains(actual.toUpperCase())) {
            return Optional.of(dim + " '" + actual + "' not permitted (allowed: " + allow + ")");
        }
        return Optional.empty();
    }
}
