package com.gme.pay.auth.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import com.gme.pay.auth.persistence.PermissionConstraintEntity.ScopeType;

/**
 * H2 (PostgreSQL-compat) slice test for V004 RBAC constraints: the
 * {@code permission_constraints} table applies, a typed constraint round-trips
 * (config_json preserved verbatim), the scope-lookup query filters by active, and
 * the scope/type CHECK constraints reject out-of-domain values. No-Docker unit
 * slice; the same schema runs against real PostgreSQL 16 in the docker-tagged ITs.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RbacConstraintMigrationTest {

    @Autowired private TestEntityManager em;
    @Autowired private PermissionConstraintRepository constraints;
    @Autowired private RoleRepository roles;

    @Test
    void v004_constraint_roundTrips_andConfigJsonPreserved() {
        Long hubAdmin = roles.findByCode("HUB_ADMIN").orElseThrow().getId();
        String json = "{\"timezone\":\"Asia/Tokyo\",\"startHour\":\"9\",\"endHour\":\"17\","
                + "\"days\":\"MON,TUE,WED,THU,FRI\"}";
        constraints.saveAndFlush(new PermissionConstraintEntity(
                ScopeType.ROLE, hubAdmin, "TIME", json, null, true, Instant.now()));
        em.clear();

        var found = constraints.findByScopeTypeAndScopeIdAndActiveTrue("ROLE", hubAdmin);
        assertThat(found).hasSize(1);
        var c = found.get(0);
        assertThat(c.getConstraintType()).isEqualTo("TIME");
        assertThat(c.getConfigJson()).isEqualTo(json); // verbatim — engine parses this map
        assertThat(c.getTenantId()).isNull(); // platform-global
        assertThat(c.getId()).isNotNull();
    }

    @Test
    void scopeLookup_excludesInactive() {
        Long roleId = 4242L;
        constraints.saveAndFlush(new PermissionConstraintEntity(
                ScopeType.ROLE, roleId, "AMOUNT", "{\"maxAmount\":\"1000\"}", null, true, Instant.now()));
        constraints.saveAndFlush(new PermissionConstraintEntity(
                ScopeType.ROLE, roleId, "AMOUNT", "{\"maxAmount\":\"5000\"}", null, false, Instant.now()));
        em.clear();

        var active = constraints.findByScopeTypeAndScopeIdAndActiveTrue("ROLE", roleId);
        assertThat(active).hasSize(1);
        assertThat(active.get(0).getConfigJson()).contains("1000");
    }

    @Test
    void scopeLookup_batchesAcrossScopeIds() {
        constraints.saveAndFlush(new PermissionConstraintEntity(
                ScopeType.PERMISSION, 11L, "LOCATION", "{\"countries\":\"JP\"}", null, true, Instant.now()));
        constraints.saveAndFlush(new PermissionConstraintEntity(
                ScopeType.PERMISSION, 22L, "DATA_FILTER", "{\"currencies\":\"KRW\"}", null, true, Instant.now()));
        em.clear();

        var found = constraints.findByScopeTypeAndScopeIdInAndActiveTrue(
                "PERMISSION", java.util.List.of(11L, 22L, 33L));
        assertThat(found).hasSize(2);
        assertThat(found).extracting(PermissionConstraintEntity::getConstraintType)
                .containsExactlyInAnyOrder("LOCATION", "DATA_FILTER");
    }

    @Test
    void checkConstraint_rejectsUnknownScopeType() {
        // ck_perm_constraints_scope only permits ROLE/PERMISSION/ROLE_PERMISSION/USER_ROLE.
        assertThatThrownBy(() -> em.getEntityManager().createNativeQuery(
                "INSERT INTO permission_constraints "
                        + "(scope_type, scope_id, constraint_type, config_json, active, created_at) "
                        + "VALUES ('GROUP', 1, 'TIME', '{}', TRUE, CURRENT_TIMESTAMP)")
                .executeUpdate()).isInstanceOf(Exception.class);
    }

    @Test
    void checkConstraint_rejectsUnknownConstraintType() {
        // ck_perm_constraints_type only permits TIME/LOCATION/AMOUNT/DATA_FILTER/APPROVAL.
        assertThatThrownBy(() -> em.getEntityManager().createNativeQuery(
                "INSERT INTO permission_constraints "
                        + "(scope_type, scope_id, constraint_type, config_json, active, created_at) "
                        + "VALUES ('ROLE', 1, 'VELOCITY', '{}', TRUE, CURRENT_TIMESTAMP)")
                .executeUpdate()).isInstanceOf(Exception.class);
    }
}
