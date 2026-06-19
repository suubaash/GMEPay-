package com.gme.pay.auth.rbac;

import java.time.Instant;
import java.util.List;

/**
 * DTOs for the RBAC management API ({@link RbacAdminController}). Grouped in one file as small
 * request/response records — the catalogue (permissions), the role↔permission grant graph,
 * temporal user-role assignments, and typed constraints. Everything is DB-driven: no permission
 * or role is hardcoded; the dashboard composes entirely from these.
 */
public final class RbacAdminDtos {

    private RbacAdminDtos() {}

    // ----------------------------------------------------------------- permissions

    public record PermissionView(Long id, String code, String resource, String action,
                                 String description, Long tenantId) {}

    public record CreatePermissionRequest(String code, String resource, String action,
                                          String description, Long tenantId) {}

    // ----------------------------------------------------------------------- roles

    /**
     * A role plus the permission codes it grants and how many principals currently hold it
     * (the dashboard's role-detail view). {@code userCount} counts distinct principals via direct
     * {@code principal_roles} ∪ active {@code user_roles} assignments.
     */
    public record RoleView(Long id, String code, String description, long userCount,
                           List<String> permissions) {}

    /** Create a role (code unique, e.g. {@code AUDIT_READ}) and optionally grant base permissions by code. */
    public record CreateRoleRequest(String code, String description, List<String> permissionCodes) {}

    /** Grant a permission to a role — by id or by code (exactly one). */
    public record GrantPermissionRequest(Long permissionId, String permissionCode, Long tenantId) {}

    // ------------------------------------------------------- user-role assignments

    /**
     * A temporal role assignment to a principal. {@code validTo}=null → permanent;
     * {@code revokedAt}!=null → revoked early. {@code active} reflects the window at read time.
     */
    public record UserRoleView(Long id, Long principalId, Long roleId, String roleCode, Long tenantId,
                               Instant validFrom, Instant validTo, String grantedBy, Instant grantedAt,
                               Instant revokedAt, boolean active) {}

    /**
     * Assign a role to a principal. Role by id or code (exactly one). {@code validFrom} defaults
     * to now; {@code validTo} optional (omit for permanent, set for a time-boxed/temporary grant).
     */
    public record AssignRoleRequest(Long roleId, String roleCode, Long tenantId,
                                    Instant validFrom, Instant validTo, String grantedBy) {}

    // ------------------------------------------------------------------ constraints

    public record ConstraintView(Long id, String scopeType, Long scopeId, String constraintType,
                                 String configJson, Long tenantId, boolean active, Instant createdAt) {}

    public record CreateConstraintRequest(String scopeType, Long scopeId, String constraintType,
                                          String configJson, Long tenantId) {}
}
