package com.gme.pay.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.core.annotation.Order;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Reactive (WebFlux) Spring Security configuration for the API Gateway.
 *
 * <p>Slice 1 — ADR-011 split: human traffic from admin-ui / partner-portal-ui bears
 * Keycloak-issued JWTs (realm {@code gmepay}); machine traffic from partners bears
 * HMAC-signed headers verified by {@link com.gme.pay.gateway.filter.HmacSignatureFilter}.
 *
 * <p>This config wires JWT validation (issuer-uri / JWKS resolved by the resource-server
 * autoconfig from {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}) and maps
 * Keycloak realm roles into Spring {@code ROLE_*} authorities so {@code @PreAuthorize}
 * / {@code hasRole(...)} expressions work consistently across services. Specifically,
 * Keycloak's realm role {@code OPERATOR} becomes {@code ROLE_OPERATOR}.
 *
 * <p>Two ordered {@link SecurityWebFilterChain}s implement the ADR-011 split (WebFlux
 * evaluates them in {@code @Order} sequence; the first whose {@code securityMatcher}
 * matches handles the request):
 * <ul>
 *   <li><b>{@code @Order(0)} partner chain</b> — {@code securityMatcher("/v1/**")},
 *       {@code permitAll}. The partner API surface is machine traffic authenticated by
 *       the HMAC-SHA256 {@link com.gme.pay.gateway.filter.HmacSignatureFilter}. That
 *       filter is a Spring Cloud Gateway {@code GlobalFilter} which runs inside the
 *       gateway {@code WebHandler} — i.e. AFTER the Spring Security {@code WebFilter}
 *       chain ({@code WebFilterChainProxy} is registered at order -100). If {@code /v1/**}
 *       required a JWT here, every JWT-less partner request would be 401'd by this
 *       WebFilter BEFORE the HMAC GlobalFilter could run. Permitting {@code /v1/**} at
 *       the security layer delegates partner authentication to the HMAC filter, which
 *       still rejects missing/invalid signatures with 401 — so this does not weaken
 *       partner security, it relocates it to the correct layer.</li>
 *   <li><b>{@code @Order(1)} default chain</b> — health + Prometheus actuator endpoints
 *       are anonymous; everything else requires a valid Keycloak JWT (realm roles mapped
 *       to {@code ROLE_*}). This governs human/admin + BFF-originated traffic.</li>
 * </ul>
 *
 * <p>CSRF is disabled because the gateway only sees machine-to-machine traffic plus
 * BFF-originated calls that themselves carry bearer tokens (not browser session cookies).
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    /**
     * Spring authority prefix that {@code hasRole("OPERATOR")} expects.
     * Visible for test usage.
     */
    public static final String ROLE_PREFIX = "ROLE_";

    /** JWT claim Keycloak uses for realm-level roles. */
    private static final String REALM_ACCESS_CLAIM = "realm_access";

    /** Inner claim under {@link #REALM_ACCESS_CLAIM} that lists the role names. */
    private static final String ROLES_CLAIM = "roles";

    /**
     * Resource-server JWT issuer URI. Read here only to log/expose for diagnostics;
     * the JWT decoder is wired by Spring Boot autoconfig from this same property.
     */
    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}")
    @SuppressWarnings({"FieldCanBeLocal", "unused"})
    private String issuerUri;

    /**
     * Partner (machine) security chain — ADR-011. Matches the partner API surface
     * {@code /v1/**} and permits it at the Spring Security layer so authentication is
     * performed by {@link com.gme.pay.gateway.filter.HmacSignatureFilter} (a Spring Cloud
     * Gateway {@code GlobalFilter}) inside the gateway {@code WebHandler}.
     *
     * <p>{@code @Order(0)} so it is consulted before the default JWT chain. Without this
     * chain, the default {@code oauth2ResourceServer().jwt()} rule (run by the Spring
     * Security {@code WebFilter} at order -100) would 401 every JWT-less partner request
     * BEFORE the HMAC GlobalFilter ever executes — the gateway's {@code GlobalFilter}s
     * live in a {@code WebHandler} dispatched only after the entire WebFilter chain. The
     * HMAC filter itself still returns 401 {@code INVALID_API_KEY}/{@code INVALID_SIGNATURE}
     * for missing/invalid signatures, so {@code permitAll} here delegates — not removes —
     * partner authentication.
     */
    @Bean
    @Order(0)
    public SecurityWebFilterChain partnerHmacSecurityFilterChain(ServerHttpSecurity http) {
        http
                .securityMatcher(ServerWebExchangeMatchers.pathMatchers("/v1/**"))
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(ex -> ex.anyExchange().permitAll());
        return http.build();
    }

    /**
     * Default (human/admin) security chain — ADR-011. Handles everything outside the
     * partner {@code /v1/**} surface: actuator health + Prometheus scrape are anonymous;
     * all other paths require a valid Keycloak JWT (realm roles mapped to {@code ROLE_*}).
     */
    @Bean
    @Order(1)
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(ex -> ex
                        // Actuator probes + Prometheus scrape are anonymous.
                        .pathMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                        .anyExchange().authenticated()
                )
                .oauth2ResourceServer(rs -> rs
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(reactiveJwtAuthenticationConverter()))
                );
        return http.build();
    }

    /**
     * Reactive adapter wrapping the role-mapping converter so the resource-server
     * filter (which is reactive in WebFlux) can use the same logic as a servlet stack.
     */
    @Bean
    public Converter<Jwt, Mono<AbstractAuthenticationToken>> reactiveJwtAuthenticationConverter() {
        return new ReactiveJwtAuthenticationConverterAdapter(keycloakJwtAuthenticationConverter());
    }

    /**
     * Maps Keycloak's {@code realm_access.roles[]} claim onto Spring authorities prefixed
     * with {@code ROLE_}, in addition to the default {@code scope}/{@code scp}-based
     * {@code SCOPE_*} authorities. Example: a Keycloak realm role {@code OPERATOR} becomes
     * the Spring authority {@code ROLE_OPERATOR}, so {@code hasRole("OPERATOR")} matches.
     */
    public static JwtAuthenticationConverter keycloakJwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter scopeConverter = new JwtGrantedAuthoritiesConverter();
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Collection<GrantedAuthority> scopeAuthorities = scopeConverter.convert(jwt);
            Collection<GrantedAuthority> realmAuthorities = extractRealmRoles(jwt);
            Set<GrantedAuthority> merged = new HashSet<>();
            if (scopeAuthorities != null) {
                merged.addAll(scopeAuthorities);
            }
            merged.addAll(realmAuthorities);
            return merged;
        });
        return converter;
    }

    /**
     * Pulls the realm role names from a Keycloak access token. Tolerates missing or
     * malformed claims (returns an empty list) so an unauthenticated/garbage JWT can
     * still flow through to the standard 401 path rather than NPE-ing.
     */
    private static Collection<GrantedAuthority> extractRealmRoles(Jwt jwt) {
        Object realmAccess = jwt.getClaims().get(REALM_ACCESS_CLAIM);
        if (!(realmAccess instanceof Map<?, ?> realmMap)) {
            return List.of();
        }
        Object rolesClaim = realmMap.get(ROLES_CLAIM);
        if (!(rolesClaim instanceof Collection<?> rolesColl)) {
            return List.of();
        }
        return Stream.ofNullable(rolesColl)
                .flatMap(Collection::stream)
                .filter(r -> r instanceof String)
                .map(Object::toString)
                .filter(r -> !r.isBlank())
                .map(r -> new SimpleGrantedAuthority(ROLE_PREFIX + r))
                .collect(Collectors.toUnmodifiableSet());
    }
}
