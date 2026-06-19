package com.gme.pay.rbac.constraint;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * AMOUNT constraint: the request amount must not exceed {@code maxAmount}. Optionally scoped
 * to a {@code currency} (the constraint applies only when the request currency matches —
 * lets you set different thresholds per currency). This encodes approval/escalation
 * boundaries (e.g. refund self-service ≤ 1000); above the threshold the request is denied
 * (and routed to the approval workflow when paired with an APPROVAL constraint).
 *
 * <p>Config: {@code maxAmount} (decimal), {@code currency} (optional).
 */
public class AmountThresholdConstraintEvaluator implements ConstraintEvaluator {

    @Override
    public ConstraintType type() {
        return ConstraintType.AMOUNT;
    }

    @Override
    public Optional<String> violation(Constraint c, ConstraintContext ctx) {
        BigDecimal max = c.getDecimal("maxAmount");
        if (max == null || ctx.amount() == null) {
            return Optional.empty();
        }
        String scopedCcy = c.get("currency");
        if (scopedCcy != null && !scopedCcy.isBlank()
                && (ctx.currency() == null || !scopedCcy.equalsIgnoreCase(ctx.currency()))) {
            return Optional.empty(); // threshold is for a different currency — N/A here
        }
        if (ctx.amount().compareTo(max) > 0) {
            return Optional.of("amount " + ctx.amount().toPlainString()
                    + (ctx.currency() != null ? " " + ctx.currency() : "")
                    + " exceeds threshold " + max.toPlainString());
        }
        return Optional.empty();
    }
}
