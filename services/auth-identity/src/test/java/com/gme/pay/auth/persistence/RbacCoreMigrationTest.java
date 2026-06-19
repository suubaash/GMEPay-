package com.gme.pay.auth.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

/**
 * H2 (PostgreSQL-compat) slice test for V003 RBAC-core: the permission catalogue
 * seed, the HUB_ADMIN/HUB_OPERATOR grants, and the temporal {@code user_roles}
 * assignment (expiry window + validity CHECK). No-Docker unit slice; the same
 * schema runs against real PostgreSQL 16 in the docker-tagged ITs.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RbacCoreMigrationTest {

    @Autowired private TestEntityManager em;
    @Autowired private PermissionRepository permissions;
    @Autowired private RolePermissionRepository rolePermissions;
    @Autowired private RoleRepository roles;
    @Autowired private MenuRepository menus;
    @Autowired private MenuPermissionRepository menuPermissions;
    @Autowired private UserRoleRepository userRoles;
    @Autowired private PrincipalRepository principals;

    @Test
    void v003_seedsCatalogue_andGrantsHubAdminAllSeven() {
        long catalogue = permissions.count();
        assertThat(catalogue).isEqualTo(11); // V003 seeded 7 + V005 added 4 approval permissions
        assertThat(permissions.findByCode("settlement.resolve_exception")).isPresent();
        assertThat(permissions.findByCode("rbac.manage")).isPresent();
        assertThat(permissions.findByCode("refund.approve_l1")).isPresent();

        Long hubAdminId = roles.findByCode("HUB_ADMIN").orElseThrow().getId();
        assertThat(rolePermissions.findByRoleId(hubAdminId)).hasSize((int) catalogue); // HUB_ADMIN holds the full catalogue

        Long hubOperatorId = roles.findByCode("HUB_OPERATOR").orElseThrow().getId();
        assertThat(rolePermissions.findByRoleId(hubOperatorId)).hasSize(3); // read-only subset
    }

    @Test
    void temporalUserRole_roundTrips_andResolvesActiveWindow() {
        PrincipalEntity p = principals.save(new PrincipalEntity(
                PrincipalEntity.Type.OPERATOR, "rbac.smoke", "RBAC Smoke", null, Instant.now()));
        Long adminRole = roles.findByCode("HUB_ADMIN").orElseThrow().getId();

        Instant now = Instant.now();
        UserRoleEntity ur = new UserRoleEntity(
                p.getId(), adminRole, null, now.minusSeconds(60), now.plusSeconds(3600), "system", now);
        userRoles.saveAndFlush(ur);
        em.clear();

        var active = userRoles.findByPrincipalIdAndRevokedAtIsNull(p.getId());
        assertThat(active).hasSize(1);
        assertThat(active.get(0).isActiveAt(now)).isTrue();
        assertThat(active.get(0).isActiveAt(now.plusSeconds(7200))).isFalse(); // past expiry
    }

    @Test
    void userRole_validityCheck_rejectsInvertedWindow() {
        Long adminRole = roles.findByCode("HUB_ADMIN").orElseThrow().getId();
        PrincipalEntity p = principals.save(new PrincipalEntity(
                PrincipalEntity.Type.OPERATOR, "rbac.badwindow", null, null, Instant.now()));
        Instant now = Instant.now();
        // valid_to <= valid_from violates ck_user_roles_validity
        UserRoleEntity bad = new UserRoleEntity(
                p.getId(), adminRole, null, now, now.minusSeconds(1), "system", now);
        assertThatThrownBy(() -> userRoles.saveAndFlush(bad)).isInstanceOf(Exception.class);
    }

    @Test
    void principalExtension_emailAndLastLogin_persist() {
        PrincipalEntity p = new PrincipalEntity(
                PrincipalEntity.Type.OPERATOR, "rbac.attrs", "Attr User", null, Instant.now());
        p.setEmail("attr.user@gmepay.com");
        Instant login = Instant.now();
        p.setLastLoginAt(login);
        principals.saveAndFlush(p);
        em.clear();

        PrincipalEntity reloaded = principals.findByUsername("rbac.attrs").orElseThrow();
        assertThat(reloaded.getEmail()).isEqualTo("attr.user@gmepay.com");
        assertThat(reloaded.getLastLoginAt()).isNotNull();
    }

    @Test
    void menuTree_withPermissionLink_roundTrips() {
        Instant now = Instant.now();
        MenuEntity parent = menus.save(new MenuEntity(
                "ops", null, "Operations", null, "dashboard", 10, "ADMIN", null, true, now));
        MenuEntity child = menus.save(new MenuEntity(
                "ops.settlement", parent.getId(), "Settlement", "/settlement", "account_balance",
                11, "ADMIN", null, true, now));
        em.flush();
        Long permId = permissions.findByCode("settlement.resolve_exception").orElseThrow().getId();
        menuPermissions.saveAndFlush(new MenuPermissionEntity(child.getId(), permId));
        em.clear();

        assertThat(menus.findByMenuTypeAndActiveTrueOrderBySortOrder("ADMIN"))
                .extracting(MenuEntity::getCode).contains("ops", "ops.settlement");
        assertThat(menuPermissions.findByMenuId(child.getId())).hasSize(1);
    }
}
