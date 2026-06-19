package com.gme.pay.rbac;

import com.gme.pay.rbac.constraint.Constraint;
import com.gme.pay.rbac.constraint.ConstraintContext;
import com.gme.pay.rbac.constraint.ConstraintDecision;
import com.gme.pay.rbac.constraint.ConstraintEngine;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Enforces {@link RequiresPermission} at the API level. For every dispatched handler, if the
 * method (or its controller class) is annotated, the caller's {@link PermissionContext} must
 * hold the required permission. On a miss: ENFORCE → 403; AUDIT → allow but record the deny.
 *
 * <p>When the static permission is held, any dynamic {@link Constraint}s attached to it
 * (supplied by the {@link ConstraintSource}) are then evaluated by the {@link ConstraintEngine}
 * against a {@link ConstraintContext} built from the request — time / location / amount /
 * data-filter / approval, cascading AND. A constraint violation denies the request the same way
 * a missing permission does. Constraints only ever <em>narrow</em> a granted capability.
 *
 * <p>Every decision (allow or deny, with reason) is emitted to the {@link RbacDecisionListener}.
 *
 * <p>Uses Spring MVC's {@link HandlerMethod} reflection — no AspectJ dependency, so any
 * service with spring-boot-starter-web can adopt it by flipping {@code gmepay.rbac.enabled}.
 */
public class RbacPermissionInterceptor implements HandlerInterceptor {

    private final RbacProperties props;
    private final RbacDecisionListener listener;
    private final ConstraintSource constraintSource;
    private final ConstraintEngine constraintEngine;

    /** Backwards-compatible: static-permission enforcement only (no dynamic constraints). */
    public RbacPermissionInterceptor(RbacProperties props, RbacDecisionListener listener) {
        this(props, listener, ConstraintSource.NONE, new ConstraintEngine());
    }

    public RbacPermissionInterceptor(RbacProperties props, RbacDecisionListener listener,
                                     ConstraintSource constraintSource, ConstraintEngine constraintEngine) {
        this.props = props;
        this.listener = listener;
        this.constraintSource = constraintSource;
        this.constraintEngine = constraintEngine;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!(handler instanceof HandlerMethod hm)) {
            return true; // static resources, etc.
        }
        RequiresPermission ann = hm.getMethodAnnotation(RequiresPermission.class);
        if (ann == null) {
            ann = hm.getBeanType().getAnnotation(RequiresPermission.class); // class-level fallback
        }
        if (ann == null) {
            return true; // handler is not permission-gated
        }

        String required = ann.value();
        PermissionContext ctx = RbacContextHolder.currentOrAnonymous();

        boolean allowed;
        String reason;
        String denyMessage;
        if (!ctx.hasPermission(required)) {
            allowed = false;
            reason = ctx.isAuthenticated() ? "principal lacks permission '" + required + "'"
                                           : "no RBAC context (unauthenticated)";
            denyMessage = "RBAC: missing permission '" + required + "'";
        } else {
            // Static permission held — narrow it with any dynamic constraints.
            ConstraintDecision cd = evaluateConstraints(required, ctx, request);
            if (cd.allowed()) {
                allowed = true;
                reason = "granted";
                denyMessage = null;
            } else {
                allowed = false;
                reason = "constraint denied: " + cd.reason();
                denyMessage = "RBAC: constraint denied: " + cd.reason();
            }
        }

        safeEmit(new RbacDecision(ctx.principalId(), ctx.tenantId(), required, allowed,
                props.getMode(), reason, request.getMethod(), request.getRequestURI()));

        if (allowed || props.getMode() == RbacMode.AUDIT) {
            return true;
        }
        response.sendError(HttpServletResponse.SC_FORBIDDEN, denyMessage);
        return false;
    }

    private ConstraintDecision evaluateConstraints(String permission, PermissionContext ctx,
                                                   HttpServletRequest request) {
        List<Constraint> constraints = constraintSource.constraintsFor(permission, ctx, request);
        if (constraints == null || constraints.isEmpty()) {
            return ConstraintDecision.allow();
        }
        return constraintEngine.evaluate(constraints, buildConstraintContext(ctx, request));
    }

    /**
     * Builds the request-time facts the engine evaluates against. Caller attributes
     * (country/region/office) come from the edge-stamped {@code X-Gme-*} headers; request
     * specifics (amount/currency/merchant/date) come from request params. The {@code "*"}
     * super-grant (CFO / break-glass) maps to {@code superuser}, bypassing every constraint.
     */
    private ConstraintContext buildConstraintContext(PermissionContext ctx, HttpServletRequest request) {
        return ConstraintContext.builder(Instant.now())
                .country(header(request, RbacHeaders.COUNTRY))
                .region(header(request, RbacHeaders.REGION))
                .office(header(request, RbacHeaders.OFFICE))
                .amount(decimalParam(request, "amount"))
                .currency(param(request, "currency"))
                .merchantId(param(request, "merchantId"))
                .requestedDate(dateParam(request, "date"))
                .superuser(ctx.permissions().contains("*"))
                .approvalGranted("true".equalsIgnoreCase(header(request, RbacHeaders.APPROVAL_GRANTED)))
                .build();
    }

    private static String header(HttpServletRequest request, String name) {
        return trimToNull(request.getHeader(name));
    }

    private static String param(HttpServletRequest request, String name) {
        return trimToNull(request.getParameter(name));
    }

    private static BigDecimal decimalParam(HttpServletRequest request, String name) {
        String v = param(request, name);
        if (v == null) {
            return null;
        }
        try {
            return new BigDecimal(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate dateParam(HttpServletRequest request, String name) {
        String v = param(request, name);
        if (v == null) {
            return null;
        }
        try {
            return LocalDate.parse(v);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String trimToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private void safeEmit(RbacDecision d) {
        try {
            listener.onDecision(d);
        } catch (RuntimeException ignored) {
            // audit must never break the request
        }
    }
}
