package com.gme.pay.bff.web;

import com.gme.pay.bff.security.TokenClaims;
import com.gme.pay.rbac.PermissionContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Fail-CLOSED authorization gate for the BFF, deciding every check from the
 * <b>verified access token</b> ({@link TokenClaims}) — never from a request header.
 *
 * <h2>What changed (gap register T0-3 / T0-4)</h2>
 * <p>This guard used to take the raw {@code X-Gme-Permissions} header value as its input, so
 * {@code curl -H 'X-Gme-Permissions: ops:operate' .../v1/admin/ops/pause} authorized a global
 * platform pause. The {@code X-Gme-*} headers are only meaningful when an edge stamps and HMAC-signs
 * them ({@link com.gme.pay.rbac.RbacClaimSigner}), and no gateway route fronts this BFF — all its
 * traffic arrives through the SPAs' Next.js {@code /api/*} rewrite, which forwards client headers
 * verbatim. <b>The raw-header path is deleted:</b> the {@code String}-argument overloads are gone
 * and every {@code require*} method now resolves the caller's permissions from JWT claims.
 *
 * <h2>Checks</h2>
 * <ul>
 *   <li>{@link #requireOps()} — money/state-affecting operator actions (pause/resume/maintenance/
 *       suspend/unsuspend, transaction resolve, webhook replay, recon rerun, platform-setting write).</li>
 *   <li>{@link #requireTxnView()} — customer-support READ surface; {@link #OPS_PERMISSION} implies it.</li>
 *   <li>{@link #requireAdminRead()} / {@link #requireAdminWrite()} — the coarse platform-operator gate
 *       applied to the whole {@code /v1/admin/**} surface by {@link com.gme.pay.bff.config.AdminSurfaceRbacInterceptor}.</li>
 *   <li>{@link #requirePartnerScope(String)} — tenant isolation for {@code /v1/portal/{partnerId}/**}.</li>
 * </ul>
 *
 * <h2>Fail closed</h2>
 * <p>An authenticated token whose {@code permissions} claim is absent or lacks the required code is
 * DENIED (403). Authenticated is not authorized. Unauthenticated callers never reach this guard —
 * {@link com.gme.pay.bff.config.BffSecurityConfig} rejects them with 401 first.
 *
 * <h2>Dev override (default = enforce)</h2>
 * <p>{@code gmepay.ops.rbac.enforce} (default {@code true}) may be set {@code false} for local
 * development so an authenticated token carrying <em>no permissions at all</em> is allowed through.
 * Even then, a token that <em>does</em> carry permissions but lacks the required one is denied — an
 * explicit, wrong permission set is never a dev accident. The flag can no longer let an
 * <em>unauthenticated</em> caller through, because authentication is enforced by the filter chain.
 */
@Component
public class OpsRbacGuard {

    /** Permission required to invoke an ops operator action. */
    static final String OPS_PERMISSION = "ops:operate";

    /**
     * Support-appropriate READ permission for the customer-support read surface
     * (transaction search + detail/status). A support agent holding {@code txn.view}
     * can look up and read a transaction WITHOUT the dangerous {@link #OPS_PERMISSION};
     * money/state-affecting actions still require {@link #OPS_PERMISSION}.
     */
    static final String TXN_VIEW_PERMISSION = "txn.view";

    /** Seeded read permission for the partner administration surface (auth-identity V003). */
    static final String PARTNER_VIEW_PERMISSION = "partner.view";

    /** Seeded write permission for the partner administration surface (auth-identity V003). */
    static final String PARTNER_MANAGE_PERMISSION = "partner.activate";

    /**
     * Permission that lets a platform operator cross-read a partner's portal data
     * (i.e. read {@code /v1/portal/{partnerId}/**} for a partner they are not scoped to).
     * A partner's own token never carries it — it is granted to HUB_ADMIN / HUB_OPERATOR only.
     */
    static final String CROSS_PARTNER_READ_PERMISSION = PARTNER_VIEW_PERMISSION;

    /**
     * Any one of these marks the caller as a platform-hub operator rather than a partner, and is
     * the minimum to READ anything under {@code /v1/admin/**}. Mirrors the permission catalogue
     * seeded in {@code auth-identity} V003/V005/V006 — a partner-scoped token holds none of them,
     * so a partner can never read the admin surface even with a valid token.
     */
    static final Set<String> HUB_READ_PERMISSIONS = Set.of(
            OPS_PERMISSION,
            TXN_VIEW_PERMISSION,
            PARTNER_VIEW_PERMISSION,
            PARTNER_MANAGE_PERMISSION,
            "rbac.manage",
            "approval.view",
            "approval.cfo_override",
            "report.generate",
            "settlement.resolve_exception",
            "inspector.view");

    /**
     * Minimum to MUTATE anything under {@code /v1/admin/**} (partner onboarding steps, KYB
     * screening, commercial terms, commission shares, credential rotation, activation…). The
     * read-only HUB_OPERATOR grant set ({@code partner.view}, {@code txn.view},
     * {@code report.generate}) deliberately does NOT appear here.
     */
    static final Set<String> HUB_WRITE_PERMISSIONS = Set.of(
            OPS_PERMISSION,
            PARTNER_MANAGE_PERMISSION,
            "rbac.manage",
            "approval.cfo_override",
            "settlement.resolve_exception");

    /** When true (default) a token with no permissions is denied; false = dev gate-off. */
    private final boolean enforce;

    public OpsRbacGuard(@Value("${gmepay.ops.rbac.enforce:true}") boolean enforce) {
        this.enforce = enforce;
    }

    /** Authorize an ops operator action or throw {@link ResponseStatusException} 403. */
    public void requireOps() {
        require("ops operator action", OPS_PERMISSION);
    }

    /**
     * Authorize a customer-support READ (transaction search + detail/status) or throw 403.
     * {@link #OPS_PERMISSION} implies read access, so an ops operator passes this gate too.
     */
    public void requireTxnView() {
        require("transaction read", TXN_VIEW_PERMISSION, OPS_PERMISSION);
    }

    /** Authorize a READ on the {@code /v1/admin/**} surface (platform-operator only). */
    public void requireAdminRead() {
        require("admin read", PARTNER_VIEW_PERMISSION, HUB_READ_PERMISSIONS.toArray(String[]::new));
    }

    /** Authorize a WRITE on the {@code /v1/admin/**} surface (partner onboarding, KYB, terms, credentials…). */
    public void requireAdminWrite() {
        require("admin write", PARTNER_MANAGE_PERMISSION, HUB_WRITE_PERMISSIONS.toArray(String[]::new));
    }

    /**
     * Tenant isolation for {@code /v1/portal/{partnerId}/**} (gap register T0-4, cross-partner IDOR).
     *
     * <p>The {@code {partnerId}} path segment is caller-supplied and therefore <b>not</b> an identity.
     * It is authorized only when it matches the partner claim of the verified token. A platform
     * operator holding {@value #CROSS_PARTNER_READ_PERMISSION} may cross-read any partner (used by
     * support/ops looking at a partner's own view); anyone else asking for a partner that is not
     * theirs gets 403 — not 404, because the path is a legitimate resource they simply may not touch.
     *
     * @param pathPartnerId the {@code {partnerId}} path variable
     * @throws ResponseStatusException 400 when blank, 403 when out of scope
     */
    public void requirePartnerScope(String pathPartnerId) {
        if (pathPartnerId == null || pathPartnerId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "partnerId is required");
        }
        PermissionContext ctx = TokenClaims.current();
        String tokenPartnerId = ctx.tenantId();
        if (tokenPartnerId != null && !tokenPartnerId.isBlank()) {
            if (Objects.equals(tokenPartnerId, pathPartnerId)) {
                return;
            }
            // A partner-scoped token asking for someone else's data: denied unless it ALSO carries
            // the explicit cross-partner ops permission (a partner token never does).
            if (ctx.hasPermission(CROSS_PARTNER_READ_PERMISSION) || ctx.hasPermission(OPS_PERMISSION)) {
                return;
            }
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "token is scoped to a different partner");
        }
        // No partner claim = platform-hub operator token. Cross-partner read stays RBAC-guarded.
        if (ctx.hasPermission(CROSS_PARTNER_READ_PERMISSION) || ctx.hasPermission(OPS_PERMISSION)) {
            return;
        }
        if (!enforce && ctx.permissions().isEmpty()) {
            return; // dev gate-off, same semantics as require(...)
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "reading partner '" + pathPartnerId + "' requires the '"
                        + CROSS_PARTNER_READ_PERMISSION + "' permission or a matching partner token");
    }

    /**
     * The audited actor for an operator action: the verified token's subject, falling back to the
     * supplied value (a legacy {@code X-Gme-Principal-Id} header or request-body field) only when
     * there is no authenticated subject, then to {@code "unknown"}.
     *
     * <p>The token subject WINS over any caller-supplied value, so an operator cannot write an
     * audit row under someone else's name.
     */
    public String actor(String fallback) {
        String subject = TokenClaims.principalId();
        if (subject != null && !subject.isBlank()) {
            return subject;
        }
        return fallback == null || fallback.isBlank() ? "unknown" : fallback;
    }

    /** Effective permissions of the verified caller — for upstreams that re-evaluate policy (4-eyes). */
    public Set<String> permissions() {
        return TokenClaims.permissions();
    }

    /**
     * Fail-closed check that the verified token carries at least one of {@code accepted}.
     * A token with an empty permission set is denied unless the dev gate-off flag is set;
     * a non-empty set that lacks all {@code accepted} codes is ALWAYS denied.
     */
    private void require(String actionLabel, String required, String... accepted) {
        Set<String> allowed = new LinkedHashSet<>();
        allowed.add(required);
        allowed.addAll(Arrays.asList(accepted));
        PermissionContext ctx = TokenClaims.current();
        Set<String> held = ctx.permissions();
        if (held.isEmpty()) {
            if (enforce) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        actionLabel + " requires the '" + required
                                + "' permission (token presents no permissions)");
            }
            return; // dev gate-off: a permission-less token is allowed through
        }
        boolean granted = held.contains("*") || allowed.stream().anyMatch(held::contains);
        if (!granted) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    actionLabel + " requires the '" + required + "' permission");
        }
    }
}
