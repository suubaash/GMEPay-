package com.gme.pay.gateway.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-unit assertions on {@link SecurityConfig#keycloakJwtAuthenticationConverter()} —
 * verifies that a Keycloak access-token shape ({@code realm_access.roles[]}) maps onto
 * Spring {@code ROLE_*} authorities, while malformed / missing role claims degrade
 * gracefully to an empty role set rather than throwing.
 *
 * <p>No Spring context is started here; we hand-build {@link Jwt} fixtures and drive the
 * converter directly.
 */
class SecurityConfigTest {

    private static final String CLAIM_REALM_ACCESS = "realm_access";

    private final JwtAuthenticationConverter converter =
            SecurityConfig.keycloakJwtAuthenticationConverter();

    @Test
    @DisplayName("Keycloak realm role OPERATOR is mapped to ROLE_OPERATOR authority")
    void operatorRealmRole_mapsToRoleOperator() {
        Jwt jwt = jwt(Map.of(CLAIM_REALM_ACCESS, Map.of("roles", List.of("OPERATOR"))));

        AbstractAuthenticationToken token = converter.convert(jwt);

        assertNotNull(token, "converter must produce an authentication token");
        Set<String> authorities = authoritiesOf(token);
        assertTrue(authorities.contains("ROLE_OPERATOR"),
                () -> "Expected ROLE_OPERATOR; got " + authorities);
    }

    @Test
    @DisplayName("Multiple realm roles are all mapped with ROLE_ prefix")
    void multipleRealmRoles_areAllMapped() {
        Jwt jwt = jwt(Map.of(CLAIM_REALM_ACCESS,
                Map.of("roles", List.of("OPERATOR", "APPROVER", "AUDITOR"))));

        Set<String> authorities = authoritiesOf(converter.convert(jwt));

        assertTrue(authorities.containsAll(List.of(
                "ROLE_OPERATOR", "ROLE_APPROVER", "ROLE_AUDITOR")),
                () -> "Expected ROLE_OPERATOR, ROLE_APPROVER, ROLE_AUDITOR; got " + authorities);
    }

    @Test
    @DisplayName("Missing realm_access claim yields no role authorities (no NPE)")
    void missingRealmAccess_yieldsNoRoles() {
        Jwt jwt = jwt(Map.of()); // no realm_access at all

        AbstractAuthenticationToken token = converter.convert(jwt);

        Set<String> authorities = authoritiesOf(token);
        assertFalse(authorities.stream().anyMatch(a -> a.startsWith("ROLE_")),
                () -> "Expected no ROLE_* authorities; got " + authorities);
    }

    @Test
    @DisplayName("Malformed realm_access.roles (non-list) yields no role authorities")
    void malformedRolesClaim_yieldsNoRoles() {
        // roles is a String instead of a List — should NOT throw
        Jwt jwt = jwt(Map.of(CLAIM_REALM_ACCESS, Map.of("roles", "OPERATOR")));

        AbstractAuthenticationToken token = converter.convert(jwt);

        Set<String> authorities = authoritiesOf(token);
        assertFalse(authorities.stream().anyMatch(a -> a.startsWith("ROLE_")),
                () -> "Expected no ROLE_* authorities for malformed claim; got " + authorities);
    }

    @Test
    @DisplayName("Scope claim still produces SCOPE_* authorities alongside realm roles")
    void scopeAuthorities_arePreservedAlongsideRealmRoles() {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .subject("alice")
                .claim("scope", "partner:read partner:write")
                .claim(CLAIM_REALM_ACCESS, Map.of("roles", List.of("OPERATOR")))
                .build();

        Set<String> authorities = authoritiesOf(converter.convert(jwt));

        assertTrue(authorities.contains("ROLE_OPERATOR"),
                () -> "Expected ROLE_OPERATOR; got " + authorities);
        assertTrue(authorities.contains("SCOPE_partner:read"),
                () -> "Expected SCOPE_partner:read; got " + authorities);
        assertTrue(authorities.contains("SCOPE_partner:write"),
                () -> "Expected SCOPE_partner:write; got " + authorities);
    }

    // ---- helpers --------------------------------------------------------

    private static Jwt jwt(Map<String, Object> extraClaims) {
        Jwt.Builder b = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .subject("alice");
        extraClaims.forEach(b::claim);
        return b.build();
    }

    private static Set<String> authoritiesOf(AbstractAuthenticationToken token) {
        return token.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toUnmodifiableSet());
    }
}
