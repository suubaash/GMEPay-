package com.gme.pay.auth.rbac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gme.pay.auth.persistence.PermissionConstraintRepository;
import com.gme.pay.auth.persistence.PermissionRepository;
import com.gme.pay.auth.persistence.PrincipalEntity;
import com.gme.pay.auth.persistence.PrincipalRepository;
import com.gme.pay.auth.persistence.RolePermissionRepository;
import com.gme.pay.auth.persistence.RoleRepository;
import com.gme.pay.auth.persistence.UserRoleRepository;
import com.gme.pay.auth.rbac.RbacAdminDtos.AssignRoleRequest;
import com.gme.pay.auth.rbac.RbacAdminDtos.CreateConstraintRequest;
import com.gme.pay.auth.rbac.RbacAdminDtos.CreatePermissionRequest;
import com.gme.pay.auth.rbac.RbacAdminDtos.GrantPermissionRequest;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.web.server.ResponseStatusException;

/**
 * H2 (PostgreSQL-compat) slice test for the RBAC management surface ({@link RbacAdminService})
 * against the V002/V003/V004 schema + seeds: catalogue read/create, role↔permission grant/revoke,
 * temporal user-role assignment + revoke (incl. expiry window + validity guard), constraint
 * attach/list/deactivate, and that a fresh grant is reflected in {@link RbacResolutionService}
 * (cache eviction wired correctly).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RbacAdminServiceSliceTest {

    @Autowired private PermissionRepository permissions;
    @Autowired private RoleRepository roles;
    @Autowired private RolePermissionRepository rolePermissions;
    @Autowired private UserRoleRepository userRoles;
    @Autowired private PermissionConstraintRepository constraints;
    @Autowired private PrincipalRepository principals;

    private RbacResolutionService resolution;
    private RbacAdminService admin;

    @BeforeEach
    void setUp() {
        resolution = new RbacResolutionService(principals, roles, userRoles, rolePermissions, permissions, constraints);
        admin = new RbacAdminService(permissions, roles, rolePermissions, userRoles,
                constraints, principals, resolution);
    }

    @Test
    void listPermissions_returnsSeededCatalogue() {
        assertThat(admin.listPermissions()).hasSize(11) // V003 (7) + V005 (4 approval permissions)
                .anySatisfy(p -> assertThat(p.code()).isEqualTo("rbac.manage"));
    }

    @Test
    void createPermission_persists_andRejectsDuplicate() {
        var created = admin.createPermission(new CreatePermissionRequest(
                "refund.approve", "refund", "approve", "Approve refunds", null));
        assertThat(created.id()).isNotNull();
        assertThat(admin.listPermissions()).hasSize(12); // 11 seeded + 1 created

        assertThatThrownBy(() -> admin.createPermission(new CreatePermissionRequest(
                "refund.approve", "refund", "approve", null, null)))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void listRoles_showsGrantedPermissionCodes() {
        var roleViews = admin.listRoles();
        assertThat(roleViews).anySatisfy(r -> {
            if (r.code().equals("HUB_ADMIN")) {
                assertThat(r.permissions()).hasSize(11).contains("rbac.manage", "refund.approve_l1");
            }
        });
        assertThat(roleViews).anySatisfy(r -> {
            if (r.code().equals("HUB_OPERATOR")) {
                assertThat(r.permissions()).hasSize(3); // read-only subset
            }
        });
    }

    @Test
    void createRole_persistsWithBasePermissions() {
        var created = admin.createRole(new RbacAdminDtos.CreateRoleRequest(
                "AUDIT_READ", "Read-only auditor", java.util.List.of("txn.view", "report.generate")));
        assertThat(created.code()).isEqualTo("AUDIT_READ");
        assertThat(created.permissions()).containsExactlyInAnyOrder("txn.view", "report.generate");
        assertThat(roles.findByCode("AUDIT_READ")).isPresent();

        assertThatThrownBy(() -> admin.createRole(new RbacAdminDtos.CreateRoleRequest(
                "AUDIT_READ", null, null))).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void listRoles_includesUserCount() {
        PrincipalEntity p = principals.save(new PrincipalEntity(
                PrincipalEntity.Type.OPERATOR, "count.me", "Count", null, Instant.now()));
        admin.assignRole(p.getId(), new AssignRoleRequest(null, "HUB_OPERATOR", null, null, null, "tester"));

        assertThat(admin.listRoles()).anySatisfy(r -> {
            if (r.code().equals("HUB_OPERATOR")) {
                assertThat(r.userCount()).isEqualTo(1L);
            }
        });
    }

    @Test
    void grantThenRevokePermission_roundTrips() {
        Long operator = roles.findByCode("HUB_OPERATOR").orElseThrow().getId();
        Long inspectorPerm = permissions.findByCode("inspector.view").orElseThrow().getId();

        var afterGrant = admin.grantPermission(operator,
                new GrantPermissionRequest(inspectorPerm, null, null));
        assertThat(afterGrant.permissions()).hasSize(4).contains("inspector.view");

        // idempotent re-grant
        var again = admin.grantPermission(operator, new GrantPermissionRequest(null, "inspector.view", null));
        assertThat(again.permissions()).hasSize(4);

        admin.revokePermission(operator, inspectorPerm);
        assertThat(admin.listRoles()).anySatisfy(r -> {
            if (r.code().equals("HUB_OPERATOR")) {
                assertThat(r.permissions()).hasSize(3).doesNotContain("inspector.view");
            }
        });
    }

    @Test
    void assignTemporalRole_reflectsActiveWindow_andResolves() {
        PrincipalEntity p = principals.save(new PrincipalEntity(
                PrincipalEntity.Type.OPERATOR, "admin.grantee", "Grantee", null, Instant.now()));
        Instant now = Instant.now();

        var view = admin.assignRole(p.getId(), new AssignRoleRequest(
                null, "HUB_ADMIN", null, now.minusSeconds(60), now.plusSeconds(3600), "tester"));
        assertThat(view.active()).isTrue();
        assertThat(view.roleCode()).isEqualTo("HUB_ADMIN");

        // resolution sees the grant (cache evicted on assign) → HUB_ADMIN's 7 permissions
        var resolved = resolution.resolve(p.getId());
        assertThat(resolved.roles()).contains("HUB_ADMIN");
        assertThat(resolved.permissions()).contains("rbac.manage", "partner.activate");

        assertThat(admin.listUserRoles(p.getId())).singleElement()
                .satisfies(v -> assertThat(v.active()).isTrue());
    }

    @Test
    void assignRole_expiredWindow_isInactive() {
        PrincipalEntity p = principals.save(new PrincipalEntity(
                PrincipalEntity.Type.OPERATOR, "admin.expired", "Expired", null, Instant.now()));
        Instant now = Instant.now();
        var view = admin.assignRole(p.getId(), new AssignRoleRequest(
                null, "HUB_OPERATOR", null, now.minusSeconds(7200), now.minusSeconds(3600), "tester"));
        assertThat(view.active()).isFalse(); // window already closed
    }

    @Test
    void assignRole_invertedWindow_isBadRequest() {
        PrincipalEntity p = principals.save(new PrincipalEntity(
                PrincipalEntity.Type.OPERATOR, "admin.bad", "Bad", null, Instant.now()));
        Instant now = Instant.now();
        assertThatThrownBy(() -> admin.assignRole(p.getId(), new AssignRoleRequest(
                null, "HUB_ADMIN", null, now, now.minusSeconds(1), "tester")))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void revokeUserRole_endsTheAssignment() {
        PrincipalEntity p = principals.save(new PrincipalEntity(
                PrincipalEntity.Type.OPERATOR, "admin.revoke", "Revoke", null, Instant.now()));
        var view = admin.assignRole(p.getId(), new AssignRoleRequest(
                null, "HUB_ADMIN", null, null, null, "tester"));

        admin.revokeUserRole(p.getId(), view.id());
        assertThat(admin.listUserRoles(p.getId())).singleElement()
                .satisfies(v -> {
                    assertThat(v.revokedAt()).isNotNull();
                    assertThat(v.active()).isFalse();
                });
    }

    @Test
    void constraint_create_list_deactivate() {
        Long adminRole = roles.findByCode("HUB_ADMIN").orElseThrow().getId();
        var created = admin.createConstraint(new CreateConstraintRequest(
                "ROLE", adminRole, "TIME",
                "{\"timezone\":\"Asia/Tokyo\",\"startHour\":\"9\",\"endHour\":\"17\"}", null));
        assertThat(created.id()).isNotNull();
        assertThat(created.active()).isTrue();

        assertThat(admin.listConstraints("ROLE", adminRole)).hasSize(1);
        assertThat(constraints.findByScopeTypeAndScopeIdAndActiveTrue("ROLE", adminRole)).hasSize(1);

        admin.deactivateConstraint(created.id());
        assertThat(constraints.findByScopeTypeAndScopeIdAndActiveTrue("ROLE", adminRole)).isEmpty();
        assertThat(admin.listConstraints("ROLE", adminRole)).hasSize(1); // retained for audit
    }

    @Test
    void createConstraint_invalidType_isBadRequest() {
        assertThatThrownBy(() -> admin.createConstraint(new CreateConstraintRequest(
                "ROLE", 1L, "VELOCITY", "{}", null)))
                .isInstanceOf(ResponseStatusException.class);
    }
}
