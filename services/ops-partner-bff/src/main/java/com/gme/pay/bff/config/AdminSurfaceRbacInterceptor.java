package com.gme.pay.bff.config;

import com.gme.pay.bff.web.OpsRbacGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * RBAC gate over the WHOLE {@code /v1/admin/**} surface (gap register T0-2 / T0-3, task 5).
 *
 * <h2>Why an interceptor</h2>
 * <p>Before this, only 6 of the BFF's 36 controllers called {@link OpsRbacGuard}; the entire
 * partner-onboarding admin surface — KYB screening, commercial terms, commission shares,
 * bank-account verification, IP allowlist / mTLS cert, credential rotation and reveal, partner
 * activation/suspension/termination — was an <b>open write</b>. Adding a call to every one of
 * those ~60 handler methods would have been 14 controllers of churn with no compile-time
 * guarantee that a newly added endpoint is covered. Registering the guard as an interceptor over
 * the URL space makes coverage the default: a controller method added tomorrow under
 * {@code /v1/admin/**} is gated the moment it exists.
 *
 * <h2>Rule</h2>
 * <ul>
 *   <li>safe methods (GET/HEAD/OPTIONS/TRACE) → {@link OpsRbacGuard#requireAdminRead()}: the caller
 *       must hold at least one platform-operator permission. A partner-scoped token holds none, so
 *       a partner can never read the admin surface even with a perfectly valid token.</li>
 *   <li>mutating methods (POST/PUT/PATCH/DELETE) → {@link OpsRbacGuard#requireAdminWrite()}: the
 *       read-only HUB_OPERATOR grant set is NOT sufficient.</li>
 * </ul>
 *
 * <p>This is a coarse surface gate, deliberately layered <em>under</em> the fine-grained per-action
 * checks the 6 ops controllers already make ({@code requireOps()} / {@code requireTxnView()}), which
 * still run afterwards and are still what authorizes a specific privileged action. Both must pass.
 *
 * <p>{@link OpsRbacGuard} throws {@code ResponseStatusException(403)}, which Spring's handler-exception
 * resolution turns into a 403 response body consistent with the rest of the BFF.
 */
public class AdminSurfaceRbacInterceptor implements HandlerInterceptor {

    private final OpsRbacGuard rbac;

    public AdminSurfaceRbacInterceptor(OpsRbacGuard rbac) {
        this.rbac = rbac;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (isSafe(request.getMethod())) {
            rbac.requireAdminRead();
        } else {
            rbac.requireAdminWrite();
        }
        return true;
    }

    /** True for read-only HTTP methods (no state change ⇒ read permission suffices). */
    static boolean isSafe(String method) {
        HttpMethod m = method == null ? null : HttpMethod.valueOf(method.toUpperCase());
        return HttpMethod.GET.equals(m)
                || HttpMethod.HEAD.equals(m)
                || HttpMethod.OPTIONS.equals(m)
                || HttpMethod.TRACE.equals(m);
    }
}
