package com.gme.pay.gateway.config;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link GatewayRoutingConfig} exposes a {@link RouteLocator} bean with the
 * full set of route ids that the GMEPay+ {@code /v1/*} partner API surface depends on.
 *
 * <p>Boots the Spring context in {@code NONE} web-environment mode — no HTTP server is
 * started and no actual proxying is exercised; we only assert the route table is wired.
 *
 * <p>Redis auto-configuration is disabled here because the gateway's reactive Redis client
 * is only needed at request time (rate-limit + idempotency caches) and would otherwise try
 * to open a Lettuce connection during context refresh.
 */
// Use RANDOM_PORT so Spring Cloud Gateway's reactive autoconfig (which needs ServerProperties +
// Netty) is satisfied. We do not exercise HTTP — only retrieve the RouteLocator bean — but the
// gateway autoconfig refuses to load without a reactive web environment.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(GatewayRouteTableTest.JwtDecoderStub.class)
@TestPropertySource(properties = {
        // Disable Redis bits that would try to connect during context start
        "spring.autoconfigure.exclude=" +
                "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration," +
                // Slice 1: prevent the OAuth2 resource-server autoconfig from trying to
                // fetch the Keycloak JWKS during context refresh. SecurityConfig still
                // loads and wires the SecurityWebFilterChain using the stub ReactiveJwtDecoder
                // bean from JwtDecoderStub below.
                "org.springframework.boot.autoconfigure.security.oauth2.resource.reactive.ReactiveOAuth2ResourceServerAutoConfiguration",
        "management.health.redis.enabled=false"
})
class GatewayRouteTableTest {

    /**
     * Supplies a {@link ReactiveJwtDecoder} so {@link SecurityConfig} can build a
     * filter chain without reaching out to Keycloak's JWKS endpoint during the route-table
     * test. We never actually authenticate a request here — the bean only needs to exist.
     */
    @TestConfiguration
    static class JwtDecoderStub {
        @Bean
        ReactiveJwtDecoder reactiveJwtDecoder() {
            return Mockito.mock(ReactiveJwtDecoder.class);
        }
    }


    /**
     * Expected route ids, one per backend service mapping declared in
     * {@link GatewayRoutingConfig}. {@code revenue-ledger} appears twice because the service
     * hosts two distinct path families ({@code /v1/revenue/**} GET and {@code /v1/journals/**}
     * POST).
     */
    private static final Set<String> EXPECTED_ROUTE_IDS = Set.of(
            "payment-executor",
            "rate-fx",
            "prefunding",
            "smart-router",
            "merchant-qr-data",
            "config-registry",
            "transaction-mgmt",
            "revenue-ledger-revenue",
            "revenue-ledger-journals",
            "settlement-reconciliation",
            "reporting-compliance",
            "qr-service"
    );

    @Autowired
    private RouteLocator routeLocator;

    @Test
    void routeLocatorBean_isPresent() {
        assertNotNull(routeLocator,
                "RouteLocator bean must be exposed by GatewayRoutingConfig");
    }

    @Test
    void allExpectedRouteIds_arePresent() {
        Set<String> actualIds = collectRoutes().stream()
                .map(Route::getId)
                .collect(Collectors.toSet());

        for (String expected : EXPECTED_ROUTE_IDS) {
            assertTrue(actualIds.contains(expected),
                    () -> "Missing gateway route id '" + expected
                            + "'. Actual ids = " + actualIds);
        }
    }

    @Test
    void noUnexpectedRouteIds_areDeclared() {
        // Guards against accidental drift — every declared route should be in EXPECTED_ROUTE_IDS.
        Set<String> actualIds = collectRoutes().stream()
                .map(Route::getId)
                .collect(Collectors.toSet());

        assertEquals(EXPECTED_ROUTE_IDS, actualIds,
                "Route id set drift: declared routes must match the documented partner API table");
    }

    @Test
    void everyRouteHasAnHttpUri() {
        // Sanity-check that the property-driven URIs resolved to http://<service>:8080 and
        // not to an empty/lb:// scheme (which would silently fail at request time).
        for (Route route : collectRoutes()) {
            String scheme = route.getUri().getScheme();
            assertTrue("http".equals(scheme) || "https".equals(scheme),
                    () -> "Route '" + route.getId() + "' must use http(s):// — got " + route.getUri());
        }
    }

    private List<Route> collectRoutes() {
        List<Route> routes = routeLocator.getRoutes().collectList().block();
        assertNotNull(routes, "RouteLocator.getRoutes() returned null");
        return routes;
    }
}
