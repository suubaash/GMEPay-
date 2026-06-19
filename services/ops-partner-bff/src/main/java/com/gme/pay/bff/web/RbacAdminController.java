package com.gme.pay.bff.web;

import com.gme.pay.bff.client.RbacAdminClient;
import com.gme.pay.bff.web.dto.PermissionDef;
import com.gme.pay.bff.web.dto.RoleSummary;
import com.gme.pay.rbac.RequiresPermission;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin RBAC centre BFF endpoints (admin-ui {@code /rbac} page → auth-identity {@code /v1/rbac/*}).
 *
 * <ul>
 *   <li>{@code GET  /v1/admin/rbac/roles} — roles with grants + holder counts ({@link RoleSummary}[])</li>
 *   <li>{@code GET  /v1/admin/rbac/permissions} — permission catalogue ({@link PermissionDef}[])</li>
 *   <li>{@code PUT  /v1/admin/rbac/roles/{role}/permissions} — set a role's full grant list</li>
 *   <li>{@code POST /v1/admin/rbac/roles} — create a role</li>
 * </ul>
 *
 * <p>Mutations require {@code rbac.manage} (enforced at the API level when {@code gmepay.rbac.enabled}).
 * Reads are open to any authenticated operator so the page renders. Backed by {@link RbacAdminClient}
 * (stub by default; live auth-identity when {@code gmepay.auth-identity.client=rest}).
 */
@RestController
@RequestMapping("/v1/admin/rbac")
public class RbacAdminController {

    private final RbacAdminClient rbac;

    public RbacAdminController(RbacAdminClient rbac) {
        this.rbac = rbac;
    }

    @GetMapping("/roles")
    public List<RoleSummary> roles() {
        return rbac.listRoles();
    }

    @GetMapping("/permissions")
    public List<PermissionDef> permissions() {
        return rbac.listPermissions();
    }

    @PutMapping("/roles/{role}/permissions")
    @RequiresPermission("rbac.manage")
    public RoleSummary putRolePermissions(@PathVariable String role, @RequestBody PutPermissionsRequest body) {
        return rbac.putRolePermissions(role, body == null ? List.of() : body.grants());
    }

    @PostMapping("/roles")
    @RequiresPermission("rbac.manage")
    public RoleSummary createRole(@RequestBody CreateRoleRequest body) {
        return rbac.createRole(body.name(), body.basePermissions());
    }

    /** PUT body: the full permission code list to assign to the role. */
    public record PutPermissionsRequest(List<String> grants) {}

    /** POST body: new role code ({@code name}) + base permission codes. */
    public record CreateRoleRequest(String name, List<String> basePermissions) {}
}
