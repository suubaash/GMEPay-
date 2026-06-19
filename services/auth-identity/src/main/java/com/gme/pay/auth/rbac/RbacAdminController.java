package com.gme.pay.auth.rbac;

import com.gme.pay.auth.rbac.RbacAdminDtos.AssignRoleRequest;
import com.gme.pay.auth.rbac.RbacAdminDtos.ConstraintView;
import com.gme.pay.auth.rbac.RbacAdminDtos.CreateConstraintRequest;
import com.gme.pay.auth.rbac.RbacAdminDtos.CreatePermissionRequest;
import com.gme.pay.auth.rbac.RbacAdminDtos.CreateRoleRequest;
import com.gme.pay.auth.rbac.RbacAdminDtos.GrantPermissionRequest;
import com.gme.pay.auth.rbac.RbacAdminDtos.PermissionView;
import com.gme.pay.auth.rbac.RbacAdminDtos.RoleView;
import com.gme.pay.auth.rbac.RbacAdminDtos.UserRoleView;
import com.gme.pay.rbac.RequiresPermission;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * RBAC management API — the dashboard's backend. Read + mutate the catalogue, the role↔permission
 * graph, temporal user-role assignments, and constraints. Distinct paths from the read-only
 * resolution API ({@link RbacResolveController}'s {@code /resolve} + {@code /principals/{id}/permissions}).
 *
 * <p>Gated by {@code rbac.manage} ({@link RequiresPermission}). Enforced at the edge (api-gateway
 * stamps claims; these admin routes require {@code rbac.manage}); the annotation is also honoured
 * in-process if a deployment enables {@code gmepay.rbac.enabled} on auth-identity (defence-in-depth).
 */
@RestController
@RequestMapping("/v1/rbac")
@RequiresPermission("rbac.manage")
public class RbacAdminController {

    private final RbacAdminService admin;

    public RbacAdminController(RbacAdminService admin) {
        this.admin = admin;
    }

    // ----------------------------------------------------------------- permissions

    @GetMapping("/permissions")
    public List<PermissionView> listPermissions() {
        return admin.listPermissions();
    }

    @PostMapping("/permissions")
    @ResponseStatus(HttpStatus.CREATED)
    public PermissionView createPermission(@RequestBody CreatePermissionRequest req) {
        return admin.createPermission(req);
    }

    // ------------------------------------------------------------------------ roles

    @GetMapping("/roles")
    public List<RoleView> listRoles() {
        return admin.listRoles();
    }

    @PostMapping("/roles")
    @ResponseStatus(HttpStatus.CREATED)
    public RoleView createRole(@RequestBody CreateRoleRequest req) {
        return admin.createRole(req);
    }

    @PostMapping("/roles/{roleId}/permissions")
    public RoleView grantPermission(@PathVariable Long roleId, @RequestBody GrantPermissionRequest req) {
        return admin.grantPermission(roleId, req);
    }

    @DeleteMapping("/roles/{roleId}/permissions/{permissionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokePermission(@PathVariable Long roleId, @PathVariable Long permissionId) {
        admin.revokePermission(roleId, permissionId);
    }

    // -------------------------------------------------------- user-role assignments

    @GetMapping("/principals/{principalId}/roles")
    public List<UserRoleView> listUserRoles(@PathVariable Long principalId) {
        return admin.listUserRoles(principalId);
    }

    @PostMapping("/principals/{principalId}/roles")
    @ResponseStatus(HttpStatus.CREATED)
    public UserRoleView assignRole(@PathVariable Long principalId, @RequestBody AssignRoleRequest req) {
        return admin.assignRole(principalId, req);
    }

    @DeleteMapping("/principals/{principalId}/roles/{userRoleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeUserRole(@PathVariable Long principalId, @PathVariable Long userRoleId) {
        admin.revokeUserRole(principalId, userRoleId);
    }

    // ------------------------------------------------------------------ constraints

    @GetMapping("/constraints")
    public List<ConstraintView> listConstraints(@RequestParam String scopeType, @RequestParam Long scopeId) {
        return admin.listConstraints(scopeType, scopeId);
    }

    @PostMapping("/constraints")
    @ResponseStatus(HttpStatus.CREATED)
    public ConstraintView createConstraint(@RequestBody CreateConstraintRequest req) {
        return admin.createConstraint(req);
    }

    @DeleteMapping("/constraints/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivateConstraint(@PathVariable Long id) {
        admin.deactivateConstraint(id);
    }
}
