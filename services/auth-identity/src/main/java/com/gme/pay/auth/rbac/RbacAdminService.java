package com.gme.pay.auth.rbac;

import com.gme.pay.auth.persistence.PermissionConstraintEntity;
import com.gme.pay.auth.persistence.PermissionConstraintRepository;
import com.gme.pay.auth.persistence.PermissionEntity;
import com.gme.pay.auth.persistence.PermissionRepository;
import com.gme.pay.auth.persistence.PrincipalRepository;
import com.gme.pay.auth.persistence.RoleEntity;
import com.gme.pay.auth.persistence.RolePermissionEntity;
import com.gme.pay.auth.persistence.RolePermissionId;
import com.gme.pay.auth.persistence.RolePermissionRepository;
import com.gme.pay.auth.persistence.RoleRepository;
import com.gme.pay.auth.persistence.UserRoleEntity;
import com.gme.pay.auth.persistence.UserRoleRepository;
import com.gme.pay.auth.rbac.RbacAdminDtos.AssignRoleRequest;
import com.gme.pay.auth.rbac.RbacAdminDtos.ConstraintView;
import com.gme.pay.auth.rbac.RbacAdminDtos.CreateConstraintRequest;
import com.gme.pay.auth.rbac.RbacAdminDtos.CreatePermissionRequest;
import com.gme.pay.auth.rbac.RbacAdminDtos.GrantPermissionRequest;
import com.gme.pay.auth.rbac.RbacAdminDtos.PermissionView;
import com.gme.pay.auth.rbac.RbacAdminDtos.RoleView;
import com.gme.pay.auth.rbac.RbacAdminDtos.UserRoleView;
import com.gme.pay.rbac.constraint.ConstraintType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The RBAC management surface: read + mutate the catalogue (permissions), the role↔permission
 * grant graph, temporal user-role assignments, and typed constraints. Backs the admin dashboard
 * and the "no hardcoded permissions / bulk ops / least privilege / temporary assignments"
 * requirements — everything is a DB write, no code deploy.
 *
 * <p>Every mutation that can change a principal's effective access evicts the
 * {@link RbacResolutionService} cache so the next token mint sees it immediately (zero-downtime).
 * A grant/revoke for one principal evicts just them; role-graph or constraint edits affect many
 * principals, so they evict all.
 */
@Service
public class RbacAdminService {

    private final PermissionRepository permissions;
    private final RoleRepository roles;
    private final RolePermissionRepository rolePermissions;
    private final UserRoleRepository userRoles;
    private final PermissionConstraintRepository constraints;
    private final PrincipalRepository principals;
    private final RbacResolutionService resolution;

    public RbacAdminService(PermissionRepository permissions, RoleRepository roles,
                            RolePermissionRepository rolePermissions, UserRoleRepository userRoles,
                            PermissionConstraintRepository constraints, PrincipalRepository principals,
                            RbacResolutionService resolution) {
        this.permissions = permissions;
        this.roles = roles;
        this.rolePermissions = rolePermissions;
        this.userRoles = userRoles;
        this.constraints = constraints;
        this.principals = principals;
        this.resolution = resolution;
    }

    // ------------------------------------------------------------------ permissions

    @Transactional(readOnly = true)
    public List<PermissionView> listPermissions() {
        return permissions.findAll().stream().map(RbacAdminService::toView).toList();
    }

    @Transactional
    public PermissionView createPermission(CreatePermissionRequest req) {
        String code = require(req.code(), "code");
        require(req.resource(), "resource");
        require(req.action(), "action");
        permissions.findByCode(code).ifPresent(p -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "permission already exists: " + code);
        });
        PermissionEntity saved = permissions.save(new PermissionEntity(
                code, req.resource().trim(), req.action().trim(), req.description(),
                req.tenantId(), Instant.now()));
        return toView(saved);
    }

    // ------------------------------------------------------------------------ roles

    @Transactional(readOnly = true)
    public List<RoleView> listRoles() {
        List<RoleEntity> all = roles.findAll();
        if (all.isEmpty()) {
            return List.of();
        }
        // Permission code lookup (id → code), gathered once to avoid N+1.
        Map<Long, String> permCodeById = new HashMap<>();
        permissions.findAll().forEach(p -> permCodeById.put(p.getId(), p.getCode()));

        Set<Long> roleIds = new HashSet<>();
        all.forEach(r -> roleIds.add(r.getId()));
        Map<Long, Set<String>> codesByRole = new HashMap<>();
        for (RolePermissionEntity rp : rolePermissions.findByRoleIdIn(roleIds)) {
            codesByRole.computeIfAbsent(rp.getRoleId(), k -> new TreeSet<>())
                    .add(permCodeById.getOrDefault(rp.getPermissionId(), "#" + rp.getPermissionId()));
        }
        Instant now = Instant.now();
        List<RoleView> out = new ArrayList<>(all.size());
        for (RoleEntity r : all) {
            out.add(new RoleView(r.getId(), r.getCode(), r.getDescription(), countHolders(r.getId(), now),
                    new ArrayList<>(codesByRole.getOrDefault(r.getId(), Set.of()))));
        }
        return out;
    }

    @Transactional
    public RoleView createRole(RbacAdminDtos.CreateRoleRequest req) {
        String code = require(req.code(), "code");
        roles.findByCode(code).ifPresent(r -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "role already exists: " + code);
        });
        RoleEntity role = roles.save(new RoleEntity(code, req.description(), Instant.now()));
        if (req.permissionCodes() != null) {
            for (String permCode : req.permissionCodes()) {
                if (permCode == null || permCode.isBlank()) {
                    continue;
                }
                Long permId = permissions.findByCode(permCode.trim()).orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.BAD_REQUEST, "permission not found: " + permCode))
                        .getId();
                rolePermissions.save(new RolePermissionEntity(role.getId(), permId, null));
            }
        }
        resolution.evictAll();
        return roleView(role);
    }

    /** Distinct principals holding a role: direct principal_roles ∪ active user_roles. */
    private long countHolders(Long roleId, Instant now) {
        Set<Long> holders = new HashSet<>(principals.findIdsByRoleId(roleId));
        for (UserRoleEntity ur : userRoles.findByRoleIdAndRevokedAtIsNull(roleId)) {
            if (ur.isActiveAt(now)) {
                holders.add(ur.getPrincipalId());
            }
        }
        return holders.size();
    }

    @Transactional
    public RoleView grantPermission(Long roleId, GrantPermissionRequest req) {
        RoleEntity role = roles.findById(roleId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "role not found: " + roleId));
        Long permId = resolvePermissionId(req.permissionId(), req.permissionCode());
        if (rolePermissions.findById(new RolePermissionId(roleId, permId)).isEmpty()) {
            rolePermissions.save(new RolePermissionEntity(roleId, permId, req.tenantId()));
            resolution.evictAll(); // role-graph edit affects every holder of this role
        }
        return roleView(role);
    }

    @Transactional
    public void revokePermission(Long roleId, Long permissionId) {
        RolePermissionId id = new RolePermissionId(roleId, permissionId);
        if (rolePermissions.findById(id).isPresent()) {
            rolePermissions.deleteById(id);
            resolution.evictAll();
        }
    }

    // -------------------------------------------------------- user-role assignments

    @Transactional(readOnly = true)
    public List<UserRoleView> listUserRoles(Long principalId) {
        Instant now = Instant.now();
        Map<Long, String> roleCodeById = new HashMap<>();
        roles.findAll().forEach(r -> roleCodeById.put(r.getId(), r.getCode()));
        return userRoles.findByPrincipalId(principalId).stream()
                .map(ur -> toView(ur, roleCodeById.get(ur.getRoleId()), now))
                .toList();
    }

    @Transactional
    public UserRoleView assignRole(Long principalId, AssignRoleRequest req) {
        principals.findById(principalId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "principal not found: " + principalId));
        Long roleId = resolveRoleId(req.roleId(), req.roleCode());
        Instant validFrom = req.validFrom() != null ? req.validFrom() : Instant.now();
        if (req.validTo() != null && !req.validTo().isAfter(validFrom)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "validTo must be after validFrom");
        }
        String grantedBy = req.grantedBy() != null && !req.grantedBy().isBlank()
                ? req.grantedBy().trim() : "system";
        UserRoleEntity saved = userRoles.save(new UserRoleEntity(
                principalId, roleId, req.tenantId(), validFrom, req.validTo(), grantedBy, Instant.now()));
        resolution.evict(principalId);
        String roleCode = roles.findById(roleId).map(RoleEntity::getCode).orElse(null);
        return toView(saved, roleCode, Instant.now());
    }

    @Transactional
    public void revokeUserRole(Long principalId, Long userRoleId) {
        UserRoleEntity ur = userRoles.findById(userRoleId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "assignment not found: " + userRoleId));
        if (!ur.getPrincipalId().equals(principalId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "assignment " + userRoleId + " does not belong to principal " + principalId);
        }
        if (ur.getRevokedAt() == null) {
            ur.revoke(Instant.now());
            userRoles.save(ur);
            resolution.evict(principalId);
        }
    }

    // ------------------------------------------------------------------ constraints

    @Transactional(readOnly = true)
    public List<ConstraintView> listConstraints(String scopeType, Long scopeId) {
        return constraints.findByScopeTypeAndScopeId(scopeType, scopeId).stream()
                .map(RbacAdminService::toView).toList();
    }

    @Transactional
    public ConstraintView createConstraint(CreateConstraintRequest req) {
        PermissionConstraintEntity.ScopeType scope = parseScope(req.scopeType());
        ConstraintType type = parseType(req.constraintType());
        if (req.scopeId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "scopeId is required");
        }
        String config = (req.configJson() == null || req.configJson().isBlank()) ? "{}" : req.configJson().trim();
        PermissionConstraintEntity saved = constraints.save(new PermissionConstraintEntity(
                scope, req.scopeId(), type.name(), config, req.tenantId(), true, Instant.now()));
        resolution.evictAll(); // a new constraint narrows access for everyone in scope
        return toView(saved);
    }

    @Transactional
    public void deactivateConstraint(Long id) {
        PermissionConstraintEntity c = constraints.findById(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "constraint not found: " + id));
        if (c.isActive()) {
            c.deactivate();
            constraints.save(c);
            resolution.evictAll();
        }
    }

    // -------------------------------------------------------------------- internals

    private Long resolvePermissionId(Long id, String code) {
        if (id != null) {
            permissions.findById(id).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "permission not found: " + id));
            return id;
        }
        if (code != null && !code.isBlank()) {
            return permissions.findByCode(code.trim()).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "permission not found: " + code)).getId();
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "permissionId or permissionCode required");
    }

    private Long resolveRoleId(Long id, String code) {
        if (id != null) {
            roles.findById(id).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "role not found: " + id));
            return id;
        }
        if (code != null && !code.isBlank()) {
            return roles.findByCode(code.trim()).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "role not found: " + code)).getId();
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "roleId or roleCode required");
    }

    private RoleView roleView(RoleEntity role) {
        Map<Long, String> permCodeById = new HashMap<>();
        permissions.findAll().forEach(p -> permCodeById.put(p.getId(), p.getCode()));
        Set<String> codes = new TreeSet<>();
        for (RolePermissionEntity rp : rolePermissions.findByRoleId(role.getId())) {
            codes.add(permCodeById.getOrDefault(rp.getPermissionId(), "#" + rp.getPermissionId()));
        }
        return new RoleView(role.getId(), role.getCode(), role.getDescription(),
                countHolders(role.getId(), Instant.now()), new ArrayList<>(codes));
    }

    private static String require(String v, String field) {
        if (v == null || v.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        return v.trim();
    }

    private static PermissionConstraintEntity.ScopeType parseScope(String s) {
        try {
            return PermissionConstraintEntity.ScopeType.valueOf(require(s, "scopeType"));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid scopeType: " + s);
        }
    }

    private static ConstraintType parseType(String s) {
        try {
            return ConstraintType.valueOf(require(s, "constraintType"));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid constraintType: " + s);
        }
    }

    private static PermissionView toView(PermissionEntity p) {
        return new PermissionView(p.getId(), p.getCode(), p.getResource(), p.getAction(),
                p.getDescription(), p.getTenantId());
    }

    private static UserRoleView toView(UserRoleEntity ur, String roleCode, Instant now) {
        return new UserRoleView(ur.getId(), ur.getPrincipalId(), ur.getRoleId(), roleCode, ur.getTenantId(),
                ur.getValidFrom(), ur.getValidTo(), ur.getGrantedBy(), ur.getGrantedAt(),
                ur.getRevokedAt(), ur.isActiveAt(now));
    }

    private static ConstraintView toView(PermissionConstraintEntity c) {
        return new ConstraintView(c.getId(), c.getScopeType(), c.getScopeId(), c.getConstraintType(),
                c.getConfigJson(), c.getTenantId(), c.isActive(), c.getCreatedAt());
    }
}
