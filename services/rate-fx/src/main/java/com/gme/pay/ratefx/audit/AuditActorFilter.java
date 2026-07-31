package com.gme.pay.ratefx.audit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves the audit actor and client IP <b>once</b> per request, before any controller runs, so a
 * rate write deep in {@link com.gme.pay.ratefx.issue.RateSnapshotAdminService} sees the same
 * principal the edge resolved.
 *
 * <p>Deliberately ordered LOWEST_PRECEDENCE: this filter never rejects anything, it only observes.
 * The security decision belongs to lib-errors' {@code InternalAuthFilter}, which runs earlier and
 * 401s an untrusted caller on the snapshot path. Running after it means the actor is resolved only
 * for admitted requests, and a bug here can never open the gate.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class AuditActorFilter extends OncePerRequestFilter {

    private final AuditActorResolver resolver;

    public AuditActorFilter(AuditActorResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // Both are memoised on the request; neither throws.
        resolver.resolve(request);
        resolver.resolveIp(request);
        chain.doFilter(request, response);
    }
}
