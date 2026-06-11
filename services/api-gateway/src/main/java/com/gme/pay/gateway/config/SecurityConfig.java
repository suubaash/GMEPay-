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
 * <p>Authorization rules:
 * <ul>
 *   <li>Health + Prometheus actuator endpoints — anonymous (probes + Prometheus scrape).</li>
 *   <li>Everything under {@code /v1/**} — requires a valid JWT. HMAC-signed partner traffic
 *       does not present a JWT but is short-circuited by {@code HmacSignatureFilter}
 *       BEFORE this filter chain (Spring Cloud Gateway global filters run ahead of the
 *       security filter chain when ordered with {@code Ordered.HIGHEST_PRECEDENCE}).
 *       For routes that do require JWT auth (BFF-originated admin calls), the realm
 *       role mapping above governs access.</li>
 *   <li>Any other path defaults to authenticated.</li>
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

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(ex -> ex
                        // Actuator probes + Prometheus scrape are anonymous.
                        .pathMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                        // Partner /v1/** surface: HMAC-signed traffic is admitted by the
                        // global HmacSignatureFilter (machine auth); JWT-bearing admin
                        // traffic is admitted here. Either way the request must be
                        // authenticated before being proxied downstream.
                        .pathMatchers("/v1/**").authenticated()
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
