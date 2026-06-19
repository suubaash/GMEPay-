package com.gme.pay.auth.rbac;

import static org.assertj.core.api.Assertions.assertThat;

import com.gme.pay.auth.persistence.PermissionConstraintEntity;
import com.gme.pay.auth.persistence.PermissionConstraintRepository;
import com.gme.pay.auth.persistence.PermissionRepository;
import com.gme.pay.auth.persistence.PrincipalEntity;
import com.gme.pay.auth.persistence.PrincipalRepository;
import com.gme.pay.auth.persistence.RoleRepository;
import com.gme.pay.auth.persistence.RolePermissionRepository;
import com.gme.pay.auth.persistence.UserRoleEntity;
import com.gme.pay.auth.persistence.UserRoleRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

/**
 * H2 slice test for {@link RbacResolutionService}: effective-permission resolution over
 * direct {@code principal_roles}, active temporal {@code user_roles}, and expiry windows.
 * Uses a fixed clock so the validity window is deterministic.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RbacResolutionServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-16T12:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Autowired private PrincipalRepository principals;
    @Autowired private RoleRepository roles;
    @Autowired private UserRoleRepository userRoles;
    @Autowired private RolePermissionRepository rolePermissions;
    @Autowired private PermissionRepository permissions;
    @Autowired private PermissionConstraintRepository permissionConstraints;

    private RbacResolutionService service() {
        return new RbacResolutionService(principals, roles, userRoles, rolePermissions, permissions,
                permissionConstraints, clock);
    }

    @Test
    void resolves_hubAdmin_viaActiveUserRole_toAllSevenPermissions() {
        PrincipalEntity p = principals.save(new PrincipalEntity(
                PrincipalEntity.Type.OPERATOR, "res.admin", "Res Admin", null, NOW));
        Long hubAdmin = roles.findByCode("HUB_ADMIN").orElseThrow().getId();
        userRoles.saveAndFlush(new UserRoleEntity(
                p.getId(), hubAdmin, null, NOW.minusSeconds(60), NOW.plusSeconds(3600), "system", NOW));

        ResolvedAccess access = service().resolve(p.getId());

        assertThat(access.roles()).containsExactly("HUB_ADMIN");
        assertThat(access.permissions()).hasSize(11) // V005 granted 4 approval permissions to HUB_ADMIN
                .contains("settlement.resolve_exception", "rbac.manage", "partner.view", "refund.approve_l1");
        assertThat(access.username()).isEqualTo("res.admin");
    }

    @Test
    void resolves_directPrincipalRole_hubOperator_toReadSubset() {
        PrincipalEntity p = new PrincipalEntity(
                PrincipalEntity.Type.OPERATOR, "res.operator", "Res Operator", null, NOW);
        p.addRole(roles.findByCode("HUB_OPERATOR").orElseThrow());
        principals.saveAndFlush(p);

        ResolvedAccess access = service().resolve(p.getId());

        assertThat(access.roles()).containsExactly("HUB_OPERATOR");
        assertThat(access.permissions())
                .containsExactlyInAnyOrder("partner.view", "txn.view", "report.generate");
    }

    @Test
    void expiredUserRole_isExcluded_fromResolution() {
        PrincipalEntity p = principals.save(new PrincipalEntity(
                PrincipalEntity.Type.OPERATOR, "res.expired", "Res Expired", null, NOW));
        Long hubAdmin = roles.findByCode("HUB_ADMIN").orElseThrow().getId();
        // window ended an hour before NOW → not active
        userRoles.saveAndFlush(new UserRoleEntity(
                p.getId(), hubAdmin, null, NOW.minusSeconds(7200), NOW.minusSeconds(3600), "system", NOW.minusSeconds(7200)));

        ResolvedAccess access = service().resolve(p.getId());

        assertThat(access.roles()).isEmpty();
        assertThat(access.permissions()).isEmpty();
    }

    @Test
    void tenantId_isPartnerSurrogate_forPartnerPrincipal() {
        PrincipalEntity p = principals.save(new PrincipalEntity(
                PrincipalEntity.Type.PARTNER, "res.partner", "Res Partner", 700L, NOW));
        ResolvedAccess access = service().resolve(p.getId());
        assertThat(access.tenantId()).isEqualTo("700");
    }

    @Test
    void resolvesActiveConstraints_encodedForTheEdge() {
        Long hubOperator = roles.findByCode("HUB_OPERATOR").orElseThrow().getId();
        permissionConstraints.saveAndFlush(new PermissionConstraintEntity(
                PermissionConstraintEntity.ScopeType.ROLE, hubOperator, "TIME",
                "{\"timezone\":\"Asia/Tokyo\",\"startHour\":\"9\",\"endHour\":\"17\"}", null, true, NOW));

        PrincipalEntity p = new PrincipalEntity(
                PrincipalEntity.Type.OPERATOR, "res.constrained", "Res Constrained", null, NOW);
        p.addRole(roles.findByCode("HUB_OPERATOR").orElseThrow());
        principals.saveAndFlush(p);

        String encoded = service().resolve(p.getId()).constraints();
        // HeaderConstraintSource wire format: TYPE:k=v;k=v  — round-trips via the downstream decoder
        assertThat(encoded).startsWith("TIME:")
                .contains("timezone=Asia/Tokyo").contains("startHour=9").contains("endHour=17");
    }

    @Test
    void noConstraints_resolvesToEmptyString() {
        PrincipalEntity p = new PrincipalEntity(
                PrincipalEntity.Type.OPERATOR, "res.unconstrained", "Res Unconstrained", null, NOW);
        p.addRole(roles.findByCode("HUB_OPERATOR").orElseThrow());
        principals.saveAndFlush(p);
        assertThat(service().resolve(p.getId()).constraints()).isEmpty();
    }
}
