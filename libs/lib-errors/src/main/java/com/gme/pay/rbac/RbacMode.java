package com.gme.pay.rbac;

/**
 * Enforcement mode for the RBAC interceptor.
 *
 * <p>{@link #AUDIT} logs the allow/deny decision but never blocks — used to roll
 * {@code @RequiresPermission} out across services safely before flipping a service to
 * {@link #ENFORCE} (which returns 403 on a missing permission). Per-service rollout
 * mitigates the risk that services which never carried permission claims start failing.
 */
public enum RbacMode {
    AUDIT,
    ENFORCE
}
