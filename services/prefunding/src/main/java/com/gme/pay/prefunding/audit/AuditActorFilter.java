package com.gme.pay.prefunding.audit;

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
 * Resolves the audit actor and client IP <b>once</b> per request, before any controller runs, and
 * parks them on the request so deep write paths agree on who is acting.
 *
 * <h2>Why a filter rather than a per-call resolution</h2>
 *
 * <p>{@link com.gme.pay.prefunding.service.PrefundingService} takes no actor argument — its methods
 * are {@code deduct(partnerId, txnRef, amount)} and friends, called from four controllers and a
 * Kafka handler. Threading an actor parameter through every one of them would be a large, churn-heavy
 * change to money-moving code. Resolving here instead gives one authoritative answer per request
 * that {@link AuditActorResolver#currentActor()} reads, so two audit rows written while serving the
 * same request can never disagree about the principal — which matters because a single request can
 * write several (a capture writes a balance movement AND, on a tier crossing, an alert).
 *
 * <p>Deliberately ordered LOWEST_PRECEDENCE: this filter never rejects anything, it only observes.
 * The security decision belongs to lib-errors' {@code InternalAuthFilter}, which runs earlier and
 * 401s an untrusted caller outright. Running after it means the actor is only resolved for requests
 * that were actually admitted, and it means a bug here can never open the gate.
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
