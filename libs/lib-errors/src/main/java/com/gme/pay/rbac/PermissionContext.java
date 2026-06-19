package com.gme.pay.rbac;

import java.util.Set;

/**
 * The resolved RBAC context of the caller for the current request: who they are
 * ({@code principalId}), which tenant they act within ({@code tenantId}, null = platform
 * hub), and their effective {@code permissions} + {@code roles}. Reconstructed from the
 * {@link RbacHeaders} the edge stamped — see {@link RbacContextFilter}.
 *
 * <p>{@code "*"} in {@code permissions} is a break-glass super-grant (used by the CFO
 * override path) that satisfies any permission check.
 */
public record PermissionContext(
        String principalId,
        String tenantId,
        Set<String> permissions,
        Set<String> roles) {

    /** Unauthenticated / no RBAC context present. */
    public static final PermissionContext ANONYMOUS =
            new PermissionContext(null, null, Set.of(), Set.of());

    public boolean isAuthenticated() {
        return principalId != null && !principalId.isBlank();
    }

    /** True if the caller holds {@code code} (or the {@code "*"} super-grant). */
    public boolean hasPermission(String code) {
        return permissions.contains("*") || permissions.contains(code);
    }

    public boolean hasRole(String roleCode) {
        return roles.contains(roleCode);
    }
}
