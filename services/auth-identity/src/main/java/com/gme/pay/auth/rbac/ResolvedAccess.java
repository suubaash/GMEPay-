package com.gme.pay.auth.rbac;

import java.util.List;

/**
 * The resolved effective access of a principal — the payload the edge stamps into the
 * {@code X-Gme-*} headers (and the JWT claims at token mint). {@code permissions} is the
 * authoritative set the downstream {@code @RequiresPermission} interceptor checks; roles are
 * carried for reference/coarse checks.
 *
 * @param principalId surrogate id
 * @param username    login / client id
 * @param tenantId    partner surrogate id as string, or null for a platform-hub operator
 * @param roles       effective role codes (direct + active temporal assignments)
 * @param permissions effective permission codes (resource.action), sorted
 * @param constraints the principal's active dynamic constraints, encoded in the
 *                    {@code HeaderConstraintSource} wire format ({@code TYPE:k=v;k=v|TYPE:...});
 *                    empty when none. The edge stamps this as {@code X-Gme-Constraints} so the
 *                    downstream constraint engine evaluates TIME/LOCATION/AMOUNT/DATA_FILTER/APPROVAL.
 */
public record ResolvedAccess(
        Long principalId,
        String username,
        String tenantId,
        List<String> roles,
        List<String> permissions,
        String constraints) {}
