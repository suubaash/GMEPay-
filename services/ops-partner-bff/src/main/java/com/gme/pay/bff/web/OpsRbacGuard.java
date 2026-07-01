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
        boolean present = permissionsHeader != null && !permissionsHeader.isBlank();
        if (!present) {
            if (enforce) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "ops operator action requires the '" + OPS_PERMISSION
                                + "' permission (no permissions presented)");
            }
            // Dev gate-off: absent header allowed.
            return;
        }
        boolean hasOps = Arrays.stream(permissionsHeader.split(","))
                .map(String::trim)
                .anyMatch(OPS_PERMISSION::equals);
        if (!hasOps) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "ops operator action requires the '" + OPS_PERMISSION + "' permission");
        }
    }
}
