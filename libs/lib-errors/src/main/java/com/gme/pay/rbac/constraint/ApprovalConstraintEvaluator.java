package com.gme.pay.rbac.constraint;

import java.util.Optional;

/**
 * APPROVAL constraint: the action requires a prior approval. Satisfied only when the request
 * carries a granted approval ({@link ConstraintContext#approvalGranted()}); otherwise it's a
 * violation that signals the caller must route through the approval workflow (P5).
 * Config (optional): {@code workflow} — the approval workflow code to route to.
 */
public class ApprovalConstraintEvaluator implements ConstraintEvaluator {

    @Override
    public ConstraintType type() {
        return ConstraintType.APPROVAL;
    }

    @Override
    public Optional<String> violation(Constraint c, ConstraintContext ctx) {
        if (ctx.approvalGranted()) {
            return Optional.empty();
        }
        String workflow = c.get("workflow");
        return Optional.of("requires approval"
                + (workflow != null && !workflow.isBlank() ? " via workflow '" + workflow + "'" : ""));
    }
}
