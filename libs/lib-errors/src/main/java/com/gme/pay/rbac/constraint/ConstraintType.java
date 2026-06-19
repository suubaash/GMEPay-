package com.gme.pay.rbac.constraint;

/**
 * The kinds of constraint that can be attached to a role/permission grant and evaluated at
 * request time by the {@link ConstraintEngine} (cascading — all must pass).
 *
 * <ul>
 *   <li>{@link #TIME} — timezone-aware business-hours / weekday window.</li>
 *   <li>{@link #LOCATION} — region / office / country allowlist.</li>
 *   <li>{@link #AMOUNT} — monetary threshold (approval/escalation boundary).</li>
 *   <li>{@link #DATA_FILTER} — currency / merchant / date-range scoping.</li>
 *   <li>{@link #APPROVAL} — requires a prior approval (defers to the approval workflow).</li>
 * </ul>
 */
public enum ConstraintType {
    TIME,
    LOCATION,
    AMOUNT,
    DATA_FILTER,
    APPROVAL
}
