package com.gme.pay.auth.rbac;

import static org.assertj.core.api.Assertions.assertThat;

import com.gme.pay.audit.AuditActors;
import com.gme.pay.auth.audit.AuthAuditEvents;
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
import com.gme.pay.auth.rbac.RbacAdminDtos.CreateRoleRequest;
import com.gme.pay.auth.rbac.RbacAdminDtos.GrantPermissionRequest;
import com.gme.pay.auth.testsupport.RecordingAuditTrail;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

/**
 * T5-1 / CISO §9 — the RBAC management surface must leave a trail. Before this, every method in
 * {@link RbacAdminService} was silent: granting the {@code "*"} super-permission (the same grant
 * that break-glasses past every approval step in the platform) produced no row anywhere, so "who
 * gave whom what authority, and when" had no answer.
 *
 * <p>What this pins, in order of what a regulator asks:
 *
 * <ol>
 *   <li>every mutating method emits exactly one row, on a chain keyed so the answer is one read;</li>
 *   <li>a grant row carries the role's permission set BEFORE and AFTER, so the delta is visible
 *       without replaying history — including the {@code "*"} case specifically;</li>
 *   <li>{@code user_roles.granted_by} is the RESOLVED actor and no longer the request body's
 *       claim, and is never the bare {@code "system"} literal;</li>
 *   <li>revocations and constraint deactivations — the WIDENING changes — are audited too, which
 *       is the half that soft-delete flags usually lose.</li>
 * </ol>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RbacAdminServiceAuditTest {

    @Autowired private PermissionRepository permissions;
    @Autowired private RoleRepository roles;
    @Autowired private RolePermissionRepository rolePermissions;
    @Autowired private UserRoleRepository userRoles;
    @Autowired private PermissionConstraintRepository constraints;
    @Autowired private PrincipalRepository principals;

    private RecordingAuditTrail audit;
    private RbacAdminService admin;

    @BeforeEach
    void setUp() {
        RbacResolutionService resolution = new RbacResolutionService(
                principals, roles, userRoles, rolePermissions, permissions, constraints);
        audit = new RecordingAuditTrail();
        admin = new RbacAdminService(permissions, roles, rolePermissions, userRoles,
                constraints, principals, resolution, audit);
    }

    @Test
    @DisplayName("createRole emits ROLE_CREATED keyed by role code, carrying its initial grants")
    void createRole_isAudited() {
        admin.createRole(new CreateRoleRequest(
                "T51_AUDITOR", "Read-only auditor", List.of("txn.view")));

        var entry = audit.only(AuthAuditEvents.ROLE_CREATED);
        assertThat(entry.aggregateType()).isEqualTo(AuthAuditEvents.ROLE);
        assertThat(entry.aggregateId()).isEqualTo("T51_AUDITOR");
        assertThat(entry.beforeJson()).isNull();
        assertThat(entry.afterJson()).contains("\"permissions\":[\"txn.view\"]");
        // A role created ALREADY holding permissions is one act by one actor — not a creation plus
        // a separate grant that could be attributed to somebody else.
        assertThat(audit.ofType(AuthAuditEvents.PERMISSION_GRANTED)).isEmpty();
    }

    @Test
    @DisplayName("granting the '*' super-permission names the role, the permission and the delta")
    void grantOfSuperPermission_isAudited_withBeforeAndAfter() {
        var star = admin.createPermission(new CreatePermissionRequest(
                "*", "*", "*", "Super-grant", null));
        var role = admin.createRole(new CreateRoleRequest("T51_BREAKGLASS", "break glass", null));
        audit.clear();

        admin.grantPermission(role.id(), new GrantPermissionRequest(star.id(), null, null));

        var entry = audit.only(AuthAuditEvents.PERMISSION_GRANTED);
        assertThat(entry.aggregateType()).isEqualTo(AuthAuditEvents.ROLE);
        // Keyed by role code: the role's whole authority history is one verifiable chain.
        assertThat(entry.aggregateId()).isEqualTo("T51_BREAKGLASS");
        // The role held nothing before...
        assertThat(entry.beforeJson()).isEqualTo("{\"permissions\":[]}");
        // ...and the row states WHAT was granted by code, not only by surrogate id, so it stays
        // readable even if the catalogue row is later renamed or removed.
        assertThat(entry.afterJson())
                .contains("\"permissionCode\":\"*\"")
                .contains("\"roleCode\":\"T51_BREAKGLASS\"")
                .contains("\"permissions\":[\"*\"]");
    }

    @Test
    @DisplayName("a duplicate grant is not a state change and emits no row")
    void duplicateGrant_isNotAudited() {
        var perm = admin.createPermission(new CreatePermissionRequest(
                "t51.view", "t51", "view", null, null));
        var role = admin.createRole(new CreateRoleRequest("T51_DUP", null, null));
        admin.grantPermission(role.id(), new GrantPermissionRequest(perm.id(), null, null));
        audit.clear();

        admin.grantPermission(role.id(), new GrantPermissionRequest(perm.id(), null, null));

        assertThat(audit.entries()).isEmpty();
    }

    @Test
    @DisplayName("revokePermission is audited — the WIDENING half of the trail")
    void revokePermission_isAudited() {
        var perm = admin.createPermission(new CreatePermissionRequest(
                "t51.approve", "t51", "approve", null, null));
        var role = admin.createRole(new CreateRoleRequest("T51_REV", null, null));
        admin.grantPermission(role.id(), new GrantPermissionRequest(perm.id(), null, null));
        audit.clear();

        admin.revokePermission(role.id(), perm.id());

        var entry = audit.only(AuthAuditEvents.PERMISSION_REVOKED);
        assertThat(entry.aggregateId()).isEqualTo("T51_REV");
        assertThat(entry.beforeJson()).contains("t51.approve");
        assertThat(entry.afterJson())
                .contains("\"permissionCode\":\"t51.approve\"")
                .contains("\"permissions\":[]");
    }

    @Test
    @DisplayName("assignRole is audited on the principal's chain and records the validity window")
    void assignRole_isAudited() {
        Long principalId = newPrincipal("t51-operator");
        var role = admin.createRole(new CreateRoleRequest("T51_ASSIGNED", null, null));
        audit.clear();

        Instant from = Instant.now();
        Instant to = from.plusSeconds(3600);
        admin.assignRole(principalId, new AssignRoleRequest(role.id(), null, null, from, to, null));

        var entry = audit.only(AuthAuditEvents.ROLE_ASSIGNED);
        assertThat(entry.aggregateType()).isEqualTo(AuthAuditEvents.PRINCIPAL);
        assertThat(entry.aggregateId()).isEqualTo("principal:" + principalId);
        assertThat(entry.afterJson())
                .contains("\"roleCode\":\"T51_ASSIGNED\"")
                // Time-boxed vs permanent is the least-privilege question about a grant.
                .contains("\"temporary\":true");
    }

    @Test
    @DisplayName("granted_by is the resolved actor, never the request body's claim, never 'system'")
    void grantedBy_comesFromTheResolvedActor_notTheBody() {
        Long principalId = newPrincipal("t51-attribution");
        var role = admin.createRole(new CreateRoleRequest("T51_ATTRIB", null, null));
        // A caller that proved itself and forwarded a verified operator subject.
        audit.actingAs(AuditActors.attested("alice@gme.com"));

        // ...while the BODY claims somebody else entirely. Before T5-1 this string went straight
        // into user_roles.granted_by, so any caller could name any granter.
        var view = admin.assignRole(principalId,
                new AssignRoleRequest(role.id(), null, null, null, null, "mallory@evil.example"));

        assertThat(view.grantedBy()).isEqualTo("alice@gme.com");
        assertThat(userRoles.findById(view.id()).orElseThrow().getGrantedBy())
                .isEqualTo("alice@gme.com");
        // The claim is not thrown away — it is recorded as a CLAIM, where it cannot be mistaken
        // for the granter.
        var entry = audit.only(AuthAuditEvents.ROLE_ASSIGNED);
        assertThat(entry.afterJson())
                .contains("\"grantedBy\":\"alice@gme.com\"")
                .contains("\"grantedByClaim\":\"mallory@evil.example\"");
    }

    @Test
    @DisplayName("with no attested caller, granted_by is 'unattributed' rather than 'system'")
    void grantedBy_withoutAttestation_isHonest() {
        Long principalId = newPrincipal("t51-unattributed");
        var role = admin.createRole(new CreateRoleRequest("T51_UNATTR", null, null));

        // RecordingAuditTrail's default actor is what a request with no proven caller resolves to.
        var view = admin.assignRole(principalId,
                new AssignRoleRequest(role.id(), null, null, null, null, null));

        assertThat(view.grantedBy()).isEqualTo(AuditActors.UNATTRIBUTED);
        // The literal that used to be the default is unwritable, and would be indistinguishable
        // from the 4-eyes carve-out if it were.
        assertThat(view.grantedBy()).isNotEqualToIgnoringCase(AuditActors.LEGACY_SYSTEM);
        assertThat(AuditActors.isAttributable(view.grantedBy())).isFalse();
    }

    @Test
    @DisplayName("revokeUserRole is audited with the window it withdrew")
    void revokeUserRole_isAudited() {
        Long principalId = newPrincipal("t51-revoked");
        var role = admin.createRole(new CreateRoleRequest("T51_UNASSIGN", null, null));
        var assigned = admin.assignRole(principalId,
                new AssignRoleRequest(role.id(), null, null, null, null, null));
        audit.clear();

        admin.revokeUserRole(principalId, assigned.id());

        var entry = audit.only(AuthAuditEvents.ROLE_UNASSIGNED);
        assertThat(entry.aggregateId()).isEqualTo("principal:" + principalId);
        assertThat(entry.beforeJson()).contains("\"roleCode\":\"T51_UNASSIGN\"");
        assertThat(entry.afterJson()).contains("\"revokedAt\":");
    }

    @Test
    @DisplayName("constraint create + deactivate are both audited, with the config that changed")
    void constraintLifecycle_isAudited() {
        var role = admin.createRole(new CreateRoleRequest("T51_CONSTRAINED", null, null));
        audit.clear();

        var created = admin.createConstraint(new CreateConstraintRequest(
                "ROLE", role.id(), "AMOUNT", "{\"maxAmount\":5000}", null));

        var createEntry = audit.only(AuthAuditEvents.CONSTRAINT_CREATED);
        assertThat(createEntry.aggregateType()).isEqualTo(AuthAuditEvents.CONSTRAINT);
        assertThat(createEntry.aggregateId()).isEqualTo("ROLE:" + role.id());
        // The config IS the control — a row without it would say a limit was attached without
        // saying what the limit was.
        assertThat(createEntry.afterJson()).contains("maxAmount");

        audit.clear();
        admin.deactivateConstraint(created.id());
        var offEntry = audit.only(AuthAuditEvents.CONSTRAINT_DEACTIVATED);
        assertThat(offEntry.aggregateId()).isEqualTo("ROLE:" + role.id());
        assertThat(offEntry.beforeJson()).contains("\"active\":true").contains("maxAmount");
        assertThat(offEntry.afterJson()).contains("\"active\":false");
    }

    @Test
    @DisplayName("createPermission is audited on the catalogue chain")
    void createPermission_isAudited() {
        admin.createPermission(new CreatePermissionRequest(
                "t51.audited", "t51", "audited", "desc", null));

        var entry = audit.only(AuthAuditEvents.PERMISSION_CREATED);
        assertThat(entry.aggregateType()).isEqualTo(AuthAuditEvents.PERMISSION);
        assertThat(entry.aggregateId()).isEqualTo("t51.audited");
    }

    @Test
    @DisplayName("every RBAC audit row is a state change, joined to the business transaction")
    void rbacRowsAreNotRejections() {
        var role = admin.createRole(new CreateRoleRequest("T51_TX", null, null));
        admin.createConstraint(new CreateConstraintRequest(
                "ROLE", role.id(), "AMOUNT", "{}", null));

        // An RBAC mutation is a successful business write: its audit row must share the
        // transaction's fate, i.e. it must NOT go down the own-transaction rejection path (which
        // would let the row commit while the grant rolled back).
        assertThat(audit.entries()).isNotEmpty();
        assertThat(audit.entries()).allSatisfy(e -> assertThat(e.rejection()).isFalse());
    }

    private Long newPrincipal(String username) {
        return principals.saveAndFlush(new PrincipalEntity(
                PrincipalEntity.Type.HUB_USER, username, username, null, Instant.now())).getId();
    }
}
