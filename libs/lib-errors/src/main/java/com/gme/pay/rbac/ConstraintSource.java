package com.gme.pay.rbac;

import com.gme.pay.rbac.constraint.Constraint;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * Supplies the dynamic {@link Constraint}s that apply to a granted permission for the current
 * request. Pluggable so a service can source them from wherever fits its deployment:
 * <ul>
 *   <li>{@link HeaderConstraintSource} (default) — decodes constraints the edge stamped onto
 *       the request, mirroring the "gateway resolves, stamps headers, services trust" model
 *       used for permissions; no per-request DB hop (meets the &lt;50ms NFR);</li>
 *   <li>a service-local cache of the {@code permission_constraints} table, for services that
 *       own the resolution path.</li>
 * </ul>
 * The {@link RbacPermissionInterceptor} calls this only <em>after</em> the static permission
 * check passes — constraints narrow an already-granted capability, they never grant one.
 */
@FunctionalInterface
public interface ConstraintSource {

    /** Constraints attached to {@code permission} for this caller/request; empty = unconstrained. */
    List<Constraint> constraintsFor(String permission, PermissionContext ctx, HttpServletRequest request);

    /** No constraints ever — the default when nothing else is wired (behaviour unchanged). */
    ConstraintSource NONE = (permission, ctx, request) -> List.of();
}
