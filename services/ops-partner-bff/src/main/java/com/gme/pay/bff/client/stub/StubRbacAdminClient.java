package com.gme.pay.bff.client.stub;

import com.gme.pay.bff.client.RbacAdminClient;
import com.gme.pay.bff.web.dto.PermissionDef;
import com.gme.pay.bff.web.dto.RoleSummary;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * In-memory {@link RbacAdminClient} so the BFF + RBAC page boot standalone (local dev / tests)
 * without auth-identity. Wired by default; {@link com.gme.pay.bff.client.rest.RestRbacAdminClient}
 * takes over when {@code gmepay.auth-identity.client=rest}.
 *
 * <p>Mirrors auth-identity's V003 seed (the 7-permission catalogue + HUB_ADMIN / HUB_OPERATOR /
 * PARTNER_API) so the page renders realistic data offline. Stateless: mutations echo the requested
 * shape back (optimistic) rather than persisting.
 */
@Component
@ConditionalOnProperty(name = "gmepay.auth-identity.client", havingValue = "stub", matchIfMissing = true)
public class StubRbacAdminClient implements RbacAdminClient {

    private static final List<PermissionDef> PERMISSIONS = List.of(
            new PermissionDef("partner.view", "partner", "view", "View partner details and configuration."),
            new PermissionDef("partner.activate", "partner", "activate", "Activate or deactivate a partner."),
            new PermissionDef("settlement.resolve_exception", "settlement", "resolve_exception",
                    "Resolve settlement reconciliation exceptions."),
            new PermissionDef("report.generate", "report", "generate", "Generate compliance and revenue reports."),
            new PermissionDef("txn.view", "txn", "view", "View transaction details and history."),
            new PermissionDef("rbac.manage", "rbac", "manage", "Manage roles and permissions (super-admin only)."),
            new PermissionDef("inspector.view", "inspector", "view",
                    "View the live request/response inspector overlay."));

    private static final List<RoleSummary> ROLES = List.of(
            new RoleSummary("HUB_ADMIN", "Platform hub administrator — full access.", 2,
                    List.of("partner.view", "partner.activate", "settlement.resolve_exception",
                            "report.generate", "txn.view", "rbac.manage", "inspector.view")),
            new RoleSummary("HUB_OPERATOR", "Hub operator — read + report generation.", 5,
                    List.of("partner.view", "report.generate", "txn.view")),
            new RoleSummary("PARTNER_API", "Partner machine identity (HMAC) — no operator console access.", 0,
                    List.of()));

    @Override
    public List<RoleSummary> listRoles() {
        return ROLES;
    }

    @Override
    public List<PermissionDef> listPermissions() {
        return PERMISSIONS;
    }

    @Override
    public RoleSummary putRolePermissions(String roleCode, List<String> grants) {
        List<String> sorted = new ArrayList<>(grants == null ? List.of() : grants);
        sorted.sort(String::compareTo);
        long count = ROLES.stream().filter(r -> r.role().equals(roleCode)).findFirst()
                .map(RoleSummary::userCount).orElse(0L);
        return new RoleSummary(roleCode, descriptionOf(roleCode), count, sorted);
    }

    @Override
    public RoleSummary createRole(String name, List<String> basePermissions) {
        List<String> sorted = new ArrayList<>(basePermissions == null ? List.of() : basePermissions);
        sorted.sort(String::compareTo);
        return new RoleSummary(name, "Custom role.", 0, sorted);
    }

    private static String descriptionOf(String roleCode) {
        return ROLES.stream().filter(r -> r.role().equals(roleCode)).findFirst()
                .map(RoleSummary::description).orElse(null);
    }
}
