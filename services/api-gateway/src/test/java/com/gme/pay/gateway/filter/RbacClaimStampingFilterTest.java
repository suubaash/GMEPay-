package com.gme.pay.gateway.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gme.pay.rbac.RbacClaimSigner;
import com.gme.pay.rbac.RbacHeaders;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Unit tests for {@link RbacClaimStampingFilter} — no Spring context. A stub resolver
 * supplies claims; a capturing chain records the (mutated) exchange the filter forwards.
 * The operator JWT is injected via the Reactor context, mimicking the gateway's reactive
 * security chain.
 */
class RbacClaimStampingFilterTest {

    /** ops.kim resolves to a real grant; everyone else is unknown (empty). */
    private final RbacClaimResolver resolver = username -> "ops.kim".equals(username)
            ? Mono.just(new RbacClaims("5", "700",
                    List.of("report.generate", "partner.view"), List.of("HUB_ADMIN"),
                    "AMOUNT:maxAmount=1000"))
            : Mono.empty();

    private final RbacClaimStampingFilter filter = new RbacClaimStampingFilter(resolver);

    private static JwtAuthenticationToken jwtAuth(String username) {
        Jwt jwt = Jwt.withTokenValue("tok").header("alg", "none")
                .claim("preferred_username", username).subject(username).build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_OPERATOR")));
    }

    @Test
    @DisplayName("operator JWT → resolved permissions stamped, spoofed inbound header overwritten")
    void operatorJwt_stampsResolvedClaims() {
        MockServerWebExchange ex = MockServerWebExchange.from(
                MockServerHttpRequest.get("/admin/reports")
                        .header(RbacHeaders.PERMISSIONS, "SPOOFED")
                        .header(RbacHeaders.CONSTRAINTS, "SPOOFED"));
        ServerWebExchange[] captured = new ServerWebExchange[1];
        GatewayFilterChain chain = e -> { captured[0] = e; return Mono.empty(); };

        filter.filter(ex, chain)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(jwtAuth("ops.kim")))
                .block();

        HttpHeaders h = captured[0].getRequest().getHeaders();
        assertEquals("report.generate,partner.view", h.getFirst(RbacHeaders.PERMISSIONS));
        assertEquals("5", h.getFirst(RbacHeaders.PRINCIPAL_ID));
        assertEquals("700", h.getFirst(RbacHeaders.TENANT_ID));
        assertEquals("HUB_ADMIN", h.getFirst(RbacHeaders.ROLES));
        // resolved constraints stamped, overwriting the spoofed inbound value
        assertEquals("AMOUNT:maxAmount=1000", h.getFirst(RbacHeaders.CONSTRAINTS));
    }

    @Test
    @DisplayName("no JWT (partner/anonymous) → client-supplied X-Gme-* headers are stripped")
    void noJwt_stripsSpoofedHeaders() {
        MockServerWebExchange ex = MockServerWebExchange.from(
                MockServerHttpRequest.get("/v1/payments")
                        .header(RbacHeaders.PERMISSIONS, "SPOOFED")
                        .header(RbacHeaders.CONSTRAINTS, "SPOOFED"));
        ServerWebExchange[] captured = new ServerWebExchange[1];
        GatewayFilterChain chain = e -> { captured[0] = e; return Mono.empty(); };

        filter.filter(ex, chain).block(); // no security context

        assertNull(captured[0].getRequest().getHeaders().getFirst(RbacHeaders.PERMISSIONS));
        assertNull(captured[0].getRequest().getHeaders().getFirst(RbacHeaders.CONSTRAINTS));
    }

    @Test
    @DisplayName("unknown principal (resolve empty) → no grant stamped, spoofed header stripped")
    void unknownPrincipal_noGrant() {
        MockServerWebExchange ex = MockServerWebExchange.from(
                MockServerHttpRequest.get("/admin/reports").header(RbacHeaders.PERMISSIONS, "SPOOFED"));
        ServerWebExchange[] captured = new ServerWebExchange[1];
        GatewayFilterChain chain = e -> { captured[0] = e; return Mono.empty(); };

        filter.filter(ex, chain)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(jwtAuth("nobody")))
                .block();

        assertNull(captured[0].getRequest().getHeaders().getFirst(RbacHeaders.PERMISSIONS));
    }

    @Test
    @DisplayName("operator JWT + signing secret → claims carry a fresh, verifiable HMAC signature")
    void operatorJwt_signsClaims() {
        RbacClaimStampingFilter signing = new RbacClaimStampingFilter(resolver, "edge-secret");
        MockServerWebExchange ex = MockServerWebExchange.from(
                MockServerHttpRequest.get("/admin/reports"));
        ServerWebExchange[] captured = new ServerWebExchange[1];
        GatewayFilterChain chain = e -> { captured[0] = e; return Mono.empty(); };

        signing.filter(ex, chain)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(jwtAuth("ops.kim")))
                .block();

        HttpHeaders h = captured[0].getRequest().getHeaders();
        String sig = h.getFirst(RbacHeaders.SIGNATURE);
        String ts = h.getFirst(RbacHeaders.SIGNATURE_TS);
        assertNotNull(sig, "signature stamped");
        assertNotNull(ts, "signature timestamp stamped");
        // The signature verifies against the exact stamped claim values under the gateway secret...
        String canonical = RbacClaimSigner.canonical(Long.parseLong(ts), "5", "700",
                "report.generate,partner.view", "HUB_ADMIN", "AMOUNT:maxAmount=1000",
                null, null, null, null);   // gateway strips + signs constraint-context attrs as absent
        assertTrue(RbacClaimSigner.verify(sig, canonical, "edge-secret"), "gateway secret verifies");
        // ...and an attacker without the secret cannot forge an accepted one.
        assertFalse(RbacClaimSigner.verify(sig, canonical, "attacker-secret"), "wrong secret rejected");
    }
}
