package com.gme.pay.bff.config;

import com.gme.pay.bff.security.TokenClaims;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Spring Security configuration for {@code ops-partner-bff} — the identity boundary this
 * service shipped without (gap register T0-1/T0-2).
 *
 * <h2>What changed</h2>
 * <p>The BFF previously had <b>no authentication layer at all</b>: its 36 controllers
 * (partner lifecycle, KYB screening, commercial terms, commission shares, credential
 * rotation/reveal, platform settings, the ops kill-switch) answered any caller that could
 * reach port 8095 — and both SPAs proxy to it via an unauthenticated Next.js
 * {@code /api/*} rewrite. This config makes it an OAuth2 <b>resource server</b>: every
 * request must carry a valid bearer JWT from the configured OIDC issuer.
 *
 * <h2>Mirrors api-gateway</h2>
 * <p>Same property style and role mapping as
 * {@code services/api-gateway/.../config/SecurityConfig.java} (ADR-011: "Keycloak for
 * humans, auth-identity for machines"), so one IdP configuration serves both. The issuer is
 * provider-neutral — {@code OIDC_ISSUER_URI} accepts any OIDC provider, and
 * {@code SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI} can pin JWKS when discovery
 * is unreachable. The difference is the stack: the gateway is reactive (WebFlux,
 * {@code SecurityWebFilterChain}), this service is servlet (MVC, {@link SecurityFilterChain}).
 *
 * <h2>Rules</h2>
 * <ul>
 *   <li>{@code /actuator/health} + {@code /actuator/health/**} — anonymous (container/k8s
 *       probes; {@code show-details=never} so nothing leaks). Nothing else is public:
 *       {@code /actuator/info}, {@code /actuator/metrics}, {@code /v3/api-docs} and
 *       {@code /swagger-ui/**} now require a token, which closes the "full internal API
 *       surface published to anyone who can reach the port" finding.</li>
 *   <li>everything else — {@code authenticated()} (default deny).</li>
 *   <li>stateless: no session is created, no cookie is issued; the token is the only
 *       credential. Missing/invalid token ⇒ <b>401</b> (not a login redirect).</li>
 *   <li>CSRF disabled: there is no cookie-backed session to forge against, and callers are
 *       SPA/bearer clients.</li>
 * </ul>
 *
 * <h2>Authorization is separate</h2>
 * <p>Authentication here answers "who are you"; <em>what</em> the identity may do is decided
 * by {@link com.gme.pay.bff.web.OpsRbacGuard} from the token's own claims (via
 * {@link TokenClaims}) — see {@link AdminSurfaceRbacInterceptor}. Permission codes are also
 * stamped as {@code PERM_<code>} authorities and realm roles as {@code ROLE_<ROLE>} so
 * standard Spring expressions work.
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableWebSecurity
public class BffSecurityConfig {

    /** Anonymous paths — liveness/readiness probes only. */
    static final String[] PUBLIC_PATHS = {"/actuator/health", "/actuator/health/**"};

    /**
     * Resource-server issuer URI. Declared here for diagnostics only; the {@code JwtDecoder}
     * is autoconfigured by Spring Boot from this same property (lazily, so no network call at
     * startup). Configured in {@code application.properties} as
     * {@code ${OIDC_ISSUER_URI:http://localhost:8090/realms/gmepay}} — identical to api-gateway.
     */
    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}")
    @SuppressWarnings({"FieldCanBeLocal", "unused"})
    private String issuerUri;

    @Bean
    public SecurityFilterChain bffSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(rs -> rs
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(bffJwtAuthenticationConverter())))
                // 401 for missing/invalid credentials rather than a redirect to a login page:
                // every caller is an SPA or a service holding a bearer token.
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        return http.build();
    }

    /**
     * Maps the verified token onto Spring authorities:
     * <ul>
     *   <li>{@code scope}/{@code scp} → {@code SCOPE_*} (Spring default),</li>
     *   <li>Keycloak {@code realm_access.roles[]} → {@code ROLE_*} (same as api-gateway, so
     *       {@code hasRole("OPERATOR")} means the same thing in both services),</li>
     *   <li>{@code permissions[]} → {@code PERM_<code>} so a permission code is visible to
     *       standard Spring expressions as well as to {@link com.gme.pay.bff.web.OpsRbacGuard}.</li>
     * </ul>
     */
    public static JwtAuthenticationConverter bffJwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter scopeConverter = new JwtGrantedAuthoritiesConverter();
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Set<GrantedAuthority> merged = new HashSet<>();
            Collection<GrantedAuthority> scopes = scopeConverter.convert(jwt);
            if (scopes != null) {
                merged.addAll(scopes);
            }
            merged.addAll(authorities(TokenClaims.realmRolesOf(jwt), TokenClaims.ROLE_PREFIX));
            merged.addAll(authorities(TokenClaims.permissionsOf(jwt), TokenClaims.AUTHORITY_PREFIX));
            return merged;
        });
        return converter;
    }

    private static Set<GrantedAuthority> authorities(Set<String> codes, String prefix) {
        Set<GrantedAuthority> out = new HashSet<>();
        for (String code : codes) {
            out.add(new SimpleGrantedAuthority(prefix + code));
        }
        return out;
    }
}
