package com.gme.pay.bff.client;

import com.gme.pay.bff.web.dto.PermissionDef;
import com.gme.pay.bff.web.dto.RoleSummary;
import java.util.List;

/**
 * BFF adapter onto auth-identity's RBAC management API ({@code /v1/rbac/*}), reshaped for the
 * Admin-UI RBAC page contract ({@code /v1/admin/rbac/*}).
 *
 * <p>Two implementations, mutually exclusive via {@code gmepay.auth-identity.client}:
 * {@link com.gme.pay.bff.client.rest.RestRbacAdminClient} (live HTTP, {@code =rest}) and
 * {@link com.gme.pay.bff.client.stub.StubRbacAdminClient} (in-memory, default) so the BFF and
 * the RBAC page run standalone for local dev / tests.
 *
 * <p>auth-identity grants/revokes one permission at a time; {@link #putRolePermissions} adapts the
 * page's "set the whole grant list" semantics by diffing against the role's current grants and
 * issuing the minimal add/remove calls.
 */
public interface RbacAdminClient {

    /** All roles with their granted permission codes + holder counts. */
    List<RoleSummary> listRoles();

    /** The permission catalogue. */
    List<PermissionDef> listPermissions();

    /** Replace a role's full permission set (diff → grant/revoke); returns the updated role. */
    RoleSummary putRolePermissions(String roleCode, List<String> grants);

    /** Create a role (code = {@code name}) with the given base permission codes. */
    RoleSummary createRole(String name, List<String> basePermissions);
}
