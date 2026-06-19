package com.gme.pay.gateway.filter;

import com.gme.pay.rbac.RbacClaimSigner;
import com.gme.pay.rbac.RbacHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * GlobalFilter — stamps the authenticated operator's resolved RBAC claims into the
 * {@code X-Gme-*} headers (see {@link RbacHeaders}) so downstream services can enforce
 * {@code @RequiresPermission} with no per-request RBAC hop. The production source of the
 * headers the lib-errors interceptor reads.
 *
 * <p>Runs after the security WebFilter chain has populated the {@link JwtAuthenticationToken}
 * (order 8, after HMAC=4 / replay=5 / idempotency=7). Behaviour:
 * <ul>
 *   <li>operator JWT present → resolve permissions (by {@code preferred_username}) and stamp;</li>
 *   <li>no JWT (e.g. partner {@code /v1/**} HMAC traffic, or anonymous) → <b>strip</b> any
 *       client-supplied {@code X-Gme-*} headers (anti-spoofing) and pass through.</li>
 * </ul>
 * Resolution is fail-open ({@link RbacClaimResolver}), so the edge never breaks on an
 * auth-identity hiccup. Active only when {@code gmepay.rbac.stamp.enabled=true}.
 *
 * <h2>Provenance signature (anti-spoof)</h2>
 * When {@code gmepay.rbac.stamp.secret} is set, every stamped bundle also carries an HMAC-SHA256
 * {@link RbacHeaders#SIGNATURE} (+ {@link RbacHeaders#SIGNATURE_TS}) over the claims, produced by
 * {@link RbacClaimSigner}. Downstream {@code RbacContextFilter}s verify it with the same secret and
 * refuse any claim bundle lacking a valid, fresh signature — so claims forged by an actor that
 * reaches a service directly (bypassing this gateway) are rejected. {@link #strip(ServerWebExchange)}
 * also drops the signature headers so a client-supplied one cannot survive on un-stamped traffic.
 */
@Component
@ConditionalOnProperty(prefix = "gmepay.rbac.stamp", name = "enabled", havingValue = "true")
public class RbacClaimStampingFilter implements GlobalFilter, Ordered {

    public static final int ORDER = 8;

    private final RbacClaimResolver resolver;
    private final String signingSecret;   // blank => stamp without a provenance signature

    /** Convenience constructor for tests (no signing secret). */
    public RbacClaimStampingFilter(RbacClaimResolver resolver) {
        this(resolver, "");
    }

    @Autowired
    public RbacClaimStampingFilter(RbacClaimResolver resolver,
                                   @Value("${gmepay.rbac.stamp.secret:}") String signingSecret) {
        this.resolver = resolver;
        this.signingSecret = signingSecret == null ? "" : signingSecret;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .flatMap(auth -> resolveAndStamp(exchange, auth))
                .defaultIfEmpty(strip(exchange))          // no security context → strip + pass
                .flatMap(chain::filter);
    }

    private Mono<ServerWebExchange> resolveAndStamp(ServerWebExchange exchange, Authentication auth) {
        if (!(auth instanceof JwtAuthenticationToken jwt) || !jwt.isAuthenticated()) {
            return Mono.just(strip(exchange));            // anonymous / non-JWT → strip + pass
        }
        String username = usernameOf(jwt);
        if (username == null || username.isBlank()) {
            return Mono.just(strip(exchange));
        }
        return resolver.resolve(username)
                .map(claims -> stamp(exchange, claims))
                .defaultIfEmpty(strip(exchange));         // resolve empty → no grant, strip spoof
    }

    private static String usernameOf(JwtAuthenticationToken jwt) {
        Jwt token = jwt.getToken();
        String preferred = token.getClaimAsString("preferred_username");
        return preferred != null ? preferred : jwt.getName();
    }

    /** Set authoritative X-Gme-* headers from the resolved claims (overwrites any inbound), then sign. */
    private ServerWebExchange stamp(ServerWebExchange exchange, RbacClaims claims) {
        String principalId = claims.principalId() == null ? "" : claims.principalId();
        String tenantId    = claims.tenantId();           // may be null → header removed
        String permissions = String.join(",", claims.permissions());
        String roles       = String.join(",", claims.roles());
        String constraints = (claims.constraints() != null && !claims.constraints().isBlank())
                ? claims.constraints() : null;            // null → header removed

        long ts = System.currentTimeMillis();
        // country/region/office/approvalGranted are not yet stamped from a trusted source (the P6
        // cross-service wiring is pending), so they are signed as absent and removed below. Binding
        // them as "" means appending one to a captured valid bundle breaks the signature.
        String sig = signingSecret.isBlank() ? null
                : RbacClaimSigner.sign(
                        RbacClaimSigner.canonical(ts, principalId, tenantId, permissions, roles, constraints,
                                null, null, null, null),
                        signingSecret);

        return exchange.mutate().request(b -> b.headers(h -> {
            h.set(RbacHeaders.PRINCIPAL_ID, principalId);
            if (tenantId != null) {
                h.set(RbacHeaders.TENANT_ID, tenantId);
            } else {
                h.remove(RbacHeaders.TENANT_ID);
            }
            h.set(RbacHeaders.PERMISSIONS, permissions);
            h.set(RbacHeaders.ROLES, roles);
            // Dynamic constraints (TIME/LOCATION/AMOUNT/DATA_FILTER/APPROVAL) the downstream engine
            // evaluates. Set only when present; otherwise strip so a stale/spoofed value can't survive.
            if (constraints != null) {
                h.set(RbacHeaders.CONSTRAINTS, constraints);
            } else {
                h.remove(RbacHeaders.CONSTRAINTS);
            }
            // Constraint-context attributes the engine evaluates LOCATION/APPROVAL rules against.
            // Not yet stamped from a trusted source, so a client must never supply them — remove any
            // inbound value (the signed canonical binds them as absent).
            h.remove(RbacHeaders.COUNTRY);
            h.remove(RbacHeaders.REGION);
            h.remove(RbacHeaders.OFFICE);
            h.remove(RbacHeaders.APPROVAL_GRANTED);
            // Provenance signature: only the gateway holds the secret, so only it can produce this.
            if (sig != null) {
                h.set(RbacHeaders.SIGNATURE, sig);
                h.set(RbacHeaders.SIGNATURE_TS, Long.toString(ts));
            } else {
                h.remove(RbacHeaders.SIGNATURE);
                h.remove(RbacHeaders.SIGNATURE_TS);
            }
        })).build();
    }

    /** Remove any client-supplied X-Gme-* headers (incl. the signature) — only the gateway may set them. */
    private static ServerWebExchange strip(ServerWebExchange exchange) {
        return exchange.mutate().request(b -> b.headers(h -> {
            h.remove(RbacHeaders.PRINCIPAL_ID);
            h.remove(RbacHeaders.TENANT_ID);
            h.remove(RbacHeaders.PERMISSIONS);
            h.remove(RbacHeaders.ROLES);
            h.remove(RbacHeaders.CONSTRAINTS);
            h.remove(RbacHeaders.COUNTRY);
            h.remove(RbacHeaders.REGION);
            h.remove(RbacHeaders.OFFICE);
            h.remove(RbacHeaders.APPROVAL_GRANTED);
            h.remove(RbacHeaders.SIGNATURE);
            h.remove(RbacHeaders.SIGNATURE_TS);
        })).build();
    }
}
