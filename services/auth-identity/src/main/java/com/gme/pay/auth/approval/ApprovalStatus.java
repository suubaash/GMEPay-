package com.gme.pay.auth.approval;

/**
 * Lifecycle of an approval request. {@code AUTO_APPROVED} is a terminal self-service outcome
 * (no sign-off needed); {@code PENDING} awaits one-or-more sequential approver steps;
 * {@code APPROVED}/{@code REJECTED} are terminal decisions.
 */
public enum ApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED,
    AUTO_APPROVED;

    public boolean isGranted() {
        return this == APPROVED || this == AUTO_APPROVED;
    }
}
