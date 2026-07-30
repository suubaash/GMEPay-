package com.gme.pay.registry.actor;

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
 * Resolves the acting identity exactly once per request, before any handler runs, and caches it
 * on the request.
 *
 * <h2>Why a filter and not just the argument resolver</h2>
 *
 * <p>{@link AuditActorArgumentResolver} covers controller parameters, but not every write path
 * receives the actor as a parameter. {@code PartnerStore.save} is the case that forced this: it
 * takes no actor argument at all and hard-coded {@code return "system";} in a private
 * {@code currentActorId()} whose javadoc described itself as a placeholder. Threading a parameter
 * from six call sites through a bitemporal SCD-6 write would be a large, risky refactor for a
 * value that is the same for the whole request.
 *
 * <p>Resolving once here and reading it via {@link AuditActorResolver#currentRequestActor()} gives
 * those paths the same answer a parameter would, and guarantees there is exactly <b>one</b>
 * resolution per request — so a request can never produce two audit rows attributed to two
 * different actors, which a per-call-site resolution could.
 *
 * <p>Ordered {@link Ordered#HIGHEST_PRECEDENCE} + 20 so it runs after correlation-id setup (which
 * wants to tag the whole request, including this filter's own logging) but before anything that
 * might write. It never rejects a request: {@link AuditActorResolver} decides whether an
 * unattested caller is refused, and that decision belongs with the rule, not with the plumbing.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ActorAttestationFilter extends OncePerRequestFilter {

    private final AuditActorResolver resolver;

    public ActorAttestationFilter(AuditActorResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            resolver.resolve(request);
            resolver.resolveIp(request);
        } catch (UnattestedActorException e) {
            // require-attestation is on and this caller could not be attested. Surfacing it here
            // (rather than at the argument resolver) means the refusal happens before any handler
            // has a chance to write, which is the point of failing closed.
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"code\":\"UNAUTHORIZED\",\"message\":\"caller identity could not be "
                            + "verified\",\"retryable\":false,\"requestId\":null}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    /**
     * Actuator and the OpenAPI surface do not write anything, and running attestation on them
     * would make a health probe log an unattested-caller warning on every scrape.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri != null
                && (uri.startsWith("/actuator") || uri.startsWith("/v3/api-docs")
                        || uri.startsWith("/swagger-ui"));
    }
}
