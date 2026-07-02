package com.gme.pay.bff.web;

import com.gme.pay.rbac.RbacHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;

/**
 * Fail-CLOSED RBAC gate for the money/state-affecting Ops operator actions
 * (pause/resume/maintenance/suspend/unsuspend, transaction resolve, webhook replay,
 * recon rerun).
 *
 * <p><b>Fail closed.</b> A privileged action requires the caller to present the ops
 * operate permission ({@value #OPS_PERMISSION}) in {@code X-Gme-Permissions}
 * ({@link RbacHeaders#PERMISSIONS}). If the header is <em>absent</em> or does not
 * contain that permission, the action is DENIED (HTTP 403). No permission ⇒ no
 * privileged action — the earlier allow-when-absent behaviour let money-affecting
 * operator actions run unauthenticated and is removed.
 *
 * <p><b>Dev override (default = enforce).</b> The single clearly-named flag
 * {@code gmepay.ops.rbac.enforce} (default {@code true}) can be set to {@code false}
 * for local development so an <em>absent</em> permissions header is allowed through
 * (mirrors the internal-auth "gate off" convention). Even with the gate off, a header
 * that is <em>present but lacks</em> {@value #OPS_PERMISSION} is still denied — an
 * explicit, wrong permission set is never a dev accident.
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

    /** When true (default) an absent permissions header is denied; false = dev gate-off. */
    private final boolean enforce;

    public OpsRbacGuard(@Value("${gmepay.ops.rbac.enforce:true}") boolean enforce) {
        this.enforce = enforce;
    }

    /**
     * Authorize an ops operator action or throw {@link ResponseStatusException} 403.
     *
     * @param permissionsHeader the raw {@code X-Gme-Permissions} header value (may be null)
     */
    public void requireOps(String permissionsHeader) {
        require(permissionsHeader, "ops operator action", OPS_PERMISSION);
    }

    /**
     * Authorize a customer-support READ (transaction search + detail/status) or throw
     * {@link ResponseStatusException} 403. Fail-closed on {@value #TXN_VIEW_PERMISSION}:
     * an absent header is denied (unless dev gate-off), and a present-but-lacking header
     * is always denied. {@link #OPS_PERMISSION} implies read access, so an ops operator
     * passes this gate too.
     *
     * @param permissionsHeader the raw {@code X-Gme-Permissions} header value (may be null)
     */
    public void requireTxnView(String permissionsHeader) {
        require(permissionsHeader, "transaction read", TXN_VIEW_PERMISSION, OPS_PERMISSION);
    }

    /**
     * Fail-closed check that {@code permissionsHeader} carries at least one of
     * {@code accepted}. Absent header is denied when {@link #enforce}; present-but-lacking
     * is always denied.
     */
    private void require(String permissionsHeader, String actionLabel, String... accepted) {
        boolean present = permissionsHeader != null && !permissionsHeader.isBlank();
        String required = accepted[0];
        if (!present) {
            if (enforce) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        actionLabel + " requires the '" + required
                                + "' permission (no permissions presented)");
            }
            // Dev gate-off: absent header allowed.
            return;
        }
        boolean granted = Arrays.stream(permissionsHeader.split(","))
                .map(String::trim)
                .anyMatch(p -> Arrays.asList(accepted).contains(p));
        if (!granted) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    actionLabel + " requires the '" + required + "' permission");
        }
    }
}
