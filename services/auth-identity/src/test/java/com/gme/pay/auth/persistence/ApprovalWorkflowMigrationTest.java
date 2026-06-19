package com.gme.pay.auth.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

/**
 * H2 (PostgreSQL-compat) slice test for V005 approval workflows: the policy/request/decision
 * tables apply, the four approval permissions seed (and grant to HUB_ADMIN), and the default
 * REFUND tiers seed with correct amount bands + ordered step permissions.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ApprovalWorkflowMigrationTest {

    @Autowired private ApprovalPolicyRepository policies;
    @Autowired private PermissionRepository permissions;
    @Autowired private RoleRepository roles;
    @Autowired private RolePermissionRepository rolePermissions;

    @Test
    void v005_seedsApprovalPermissions_andGrantsToHubAdmin() {
        assertThat(permissions.findByCode("refund.approve_l1")).isPresent();
        assertThat(permissions.findByCode("refund.approve_l2")).isPresent();
        assertThat(permissions.findByCode("approval.cfo_override")).isPresent();
        assertThat(permissions.findByCode("approval.view")).isPresent();

        Long hubAdmin = roles.findByCode("HUB_ADMIN").orElseThrow().getId();
        var adminPermIds = rolePermissions.findByRoleId(hubAdmin).stream()
                .map(RolePermissionEntity::getPermissionId).toList();
        Long cfoPermId = permissions.findByCode("approval.cfo_override").orElseThrow().getId();
        assertThat(adminPermIds).contains(cfoPermId);
    }

    @Test
    void v005_seedsDefaultRefundTiers() {
        var refundPolicies = policies.findByRequestTypeAndActiveTrueOrderByMinAmountAsc("REFUND");
        assertThat(refundPolicies).hasSize(3);

        var selfServe = refundPolicies.get(0);
        assertThat(selfServe.getTierLabel()).isEqualTo("SELF_SERVE");
        assertThat(selfServe.isAutoApprove()).isTrue();
        assertThat(selfServe.steps()).isEmpty();
        assertThat(selfServe.matchesAmount(new BigDecimal("999.99"))).isTrue();
        assertThat(selfServe.matchesAmount(new BigDecimal("1000.00"))).isFalse(); // band is [0, 1000)

        var l1 = refundPolicies.get(1);
        assertThat(l1.getTierLabel()).isEqualTo("L1");
        assertThat(l1.steps()).containsExactly("refund.approve_l1");
        assertThat(l1.matchesAmount(new BigDecimal("1000.00"))).isTrue();
        assertThat(l1.matchesAmount(new BigDecimal("5000.00"))).isFalse();

        var l2 = refundPolicies.get(2);
        assertThat(l2.getTierLabel()).isEqualTo("L2_CFO");
        assertThat(l2.steps()).containsExactly("refund.approve_l1", "refund.approve_l2");
        assertThat(l2.getMaxAmount()).isNull(); // no cap
        assertThat(l2.matchesAmount(new BigDecimal("1000000"))).isTrue();
    }
}
