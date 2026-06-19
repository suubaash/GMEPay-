package com.gme.pay.auth.rbac;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gme.pay.auth.persistence.PermissionConstraintEntity;
import com.gme.pay.auth.persistence.PermissionConstraintRepository;
import com.gme.pay.auth.persistence.PermissionRepository;
import com.gme.pay.auth.persistence.PrincipalEntity;
import com.gme.pay.auth.persistence.PrincipalRepository;
import com.gme.pay.auth.persistence.RoleEntity;
import com.gme.pay.auth.persistence.RolePermissionEntity;
import com.gme.pay.auth.persistence.RolePermissionRepository;
import com.gme.pay.auth.persistence.RoleRepository;
import com.gme.pay.auth.persistence.UserRoleRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Resolves a principal's <em>effective</em> access: the union of roles granted directly
 * ({@code principal_roles}, V002) and via active temporal assignments ({@code user_roles}
 * within their validity window, not revoked), expanded through {@code role_permissions} into
 * permission codes. This runs at token mint / refresh — never on the per-request hot path —
 * so the {@code X-Gme-Permissions} the edge stamps lets downstream services enforce with no
 * RBAC network hop (the &lt;50ms NFR).
 *
 * <p>A small in-JVM TTL cache fronts resolution; {@link #evict(Long)} / {@link #evictAll()}
 * are called on any grant/revoke/role-edit so permission changes take effect without a
 * redeploy (zero-downtime requirement).
 */
@Service
public class RbacResolutionService {

    private static final Duration TTL = Duration.ofSeconds(60);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PrincipalRepository principals;
    private final RoleRepository roles;
    private final UserRoleRepository userRoles;
    private final RolePermissionRepository rolePermissions;
    private final PermissionRepository permissions;
    private final PermissionConstraintRepository permissionConstraints;
    private final Clock clock;

    private final Map<Long, Cached> cache = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public RbacResolutionService(PrincipalRepository principals, RoleRepository roles,
                                 UserRoleRepository userRoles, RolePermissionRepository rolePermissions,
                                 PermissionRepository permissions,
                                 PermissionConstraintRepository permissionConstraints) {
        this(principals, roles, userRoles, rolePermissions, permissions, permissionConstraints, Clock.systemUTC());
    }

    /** Test seam: inject a fixed clock for deterministic expiry-window behaviour. */
    RbacResolutionService(PrincipalRepository principals, RoleRepository roles,
                          UserRoleRepository userRoles, RolePermissionRepository rolePermissions,
                          PermissionRepository permissions, PermissionConstraintRepository permissionConstraints,
                          Clock clock) {
        this.principals = principals;
        this.roles = roles;
        this.userRoles = userRoles;
        this.rolePermissions = rolePermissions;
        this.permissions = permissions;
        this.permissionConstraints = permissionConstraints;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ResolvedAccess resolve(Long principalId) {
        Instant now = clock.instant();
        Cached c = cache.get(principalId);
        if (c != null && c.expiresAt.isAfter(now)) {
            return c.value;
        }
        ResolvedAccess resolved = compute(principalId, now);
        cache.put(principalId, new Cached(resolved, now.plus(TTL)));
        return resolved;
    }

    @Transactional(readOnly = true)
    public ResolvedAccess resolveByUsername(String username) {
        PrincipalEntity p = principals.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "principal not found: " + username));
        return resolve(p.getId());
    }

    /** Drop a principal's cached resolution — call after any grant/revoke affecting them. */
    public void evict(Long principalId) {
        cache.remove(principalId);
    }

    /** Drop all cached resolutions — call after a role↔permission edit (affects many principals). */
    public void evictAll() {
        cache.clear();
    }

    private ResolvedAccess compute(Long principalId, Instant now) {
        PrincipalEntity p = principals.findById(principalId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "principal not found: " + principalId));

        // Effective role ids: direct (principal_roles) + active temporal (user_roles).
        Set<Long> roleIds = new HashSet<>();
        for (RoleEntity r : p.getRoles()) {
            roleIds.add(r.getId());
        }
        userRoles.findByPrincipalIdAndRevokedAtIsNull(principalId).stream()
                .filter(ur -> ur.isActiveAt(now))
                .forEach(ur -> roleIds.add(ur.getRoleId()));

        Set<String> roleCodes = new TreeSet<>();
        Set<String> permCodes = new TreeSet<>();
        Set<Long> permIds = new HashSet<>();
        if (!roleIds.isEmpty()) {
            roles.findAllById(roleIds).forEach(r -> roleCodes.add(r.getCode()));

            for (RolePermissionEntity rp : rolePermissions.findByRoleIdIn(roleIds)) {
                permIds.add(rp.getPermissionId());
            }
            if (!permIds.isEmpty()) {
                permissions.findAllById(permIds).forEach(pe -> permCodes.add(pe.getCode()));
            }
        }

        String tenantId = p.getPartnerId() == null ? null : String.valueOf(p.getPartnerId());
        return new ResolvedAccess(principalId, p.getUsername(), tenantId,
                new ArrayList<>(roleCodes), new ArrayList<>(permCodes),
                resolveConstraints(roleIds, permIds));
    }

    /**
     * Effective active constraints attached to the principal's roles + granted permissions, encoded
     * in the {@code HeaderConstraintSource} wire format ({@code TYPE:k=v;k=v|TYPE:...}). The downstream
     * applies these to the gated action (the engine fails closed on an unknown type), so a constraint
     * on any of the caller's roles/permissions narrows their actions — matching the header model.
     */
    private String resolveConstraints(Set<Long> roleIds, Set<Long> permIds) {
        List<PermissionConstraintEntity> all = new ArrayList<>();
        if (!roleIds.isEmpty()) {
            all.addAll(permissionConstraints.findByScopeTypeAndScopeIdInAndActiveTrue(
                    "ROLE", new ArrayList<>(roleIds)));
        }
        if (!permIds.isEmpty()) {
            all.addAll(permissionConstraints.findByScopeTypeAndScopeIdInAndActiveTrue(
                    "PERMISSION", new ArrayList<>(permIds)));
        }
        if (all.isEmpty()) {
            return "";
        }
        List<String> encoded = new ArrayList<>();
        for (PermissionConstraintEntity c : all) {
            Map<String, Object> cfg = parseConfig(c.getConfigJson());
            String body = cfg.entrySet().stream()
                    .map(e -> e.getKey() + "=" + String.valueOf(e.getValue()))
                    .collect(Collectors.joining(";"));
            encoded.add(c.getConstraintType() + ":" + body);
        }
        return String.join("|", encoded);
    }

    private static Map<String, Object> parseConfig(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            return Map.of(); // malformed config → no constraint emitted (fail-soft on encode)
        }
    }

    private record Cached(ResolvedAccess value, Instant expiresAt) {}
}
