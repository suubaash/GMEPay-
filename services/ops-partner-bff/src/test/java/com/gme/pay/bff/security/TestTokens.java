package com.gme.pay.bff.security;

import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Test-only helper that places a <em>verified-shaped</em> access token in the
 * {@link SecurityContextHolder}, so controller tests exercise the same authorization path as
 * production: {@link com.gme.pay.bff.web.OpsRbacGuard} → {@link TokenClaims} → token claims.
 *
 * <p>Tests use {@code MockMvcBuilders.standaloneSetup(...)}, which does NOT run the Spring
 * Security filter chain — so the context is set directly here rather than by decoding a real
 * token. That is the correct seam: the filter chain's job (verify issuer/signature/expiry and
 * populate the context) is covered separately by
 * {@code com.gme.pay.bff.security.BffSecurityFilterChainTest}; this helper covers "given a
 * verified identity, what is authorized".
 *
 * <p>Always pair with {@link #clear()} in an {@code @AfterEach} — the holder is thread-bound and
 * would otherwise leak into the next test.
 */
public final class TestTokens {

    /** Default subject used when a test does not care who the operator is. */
    public static final String DEFAULT_SUBJECT = "ops-operator-1";

    private TestTokens() {}

    /**
     * Authenticate as a platform-hub operator (no partner claim) holding {@code permissions}.
     * Use {@link #authenticate(String, String, String...)} when the subject matters.
     */
    public static void hubOperator(String... permissions) {
        authenticate(DEFAULT_SUBJECT, null, permissions);
    }

    /** Authenticate as a token scoped to {@code partnerId} holding {@code permissions}. */
    public static void partner(String partnerId, String... permissions) {
        authenticate("partner-user@" + partnerId, partnerId, permissions);
    }

    /**
     * Authenticate with an explicit subject, partner claim and permission set — the general form.
     *
     * @param subject     the {@code sub} claim (the audited actor)
     * @param partnerId   the {@code partner_id} claim, or {@code null} for a hub operator
     * @param permissions permission codes for the {@code permissions} claim
     */
    public static void authenticate(String subject, String partnerId, String... permissions) {
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt(subject, partnerId, permissions), authorities(permissions)));
    }

    /**
     * Authenticate with a NON-JWT token whose permissions ride on {@code PERM_*} authorities —
     * covers the {@link TokenClaims} authority-based branch.
     */
    public static void authenticateWithAuthorities(String subject, String... permissions) {
        TestingAuthenticationToken token =
                new TestingAuthenticationToken(subject, "n/a", new ArrayList<>(authorities(permissions)));
        token.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    /** Builds the {@link Jwt} shape this service expects, without signing anything. */
    public static Jwt jwt(String subject, String partnerId, String... permissions) {
        Jwt.Builder b = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .subject(subject)
                .issuedAt(Instant.now().minusSeconds(60))
                .expiresAt(Instant.now().plusSeconds(600))
                .claim(TokenClaims.PERMISSIONS_CLAIM, Arrays.asList(permissions))
                .claim(TokenClaims.REALM_ACCESS_CLAIM, Map.of(TokenClaims.ROLES_CLAIM, List.of("OPERATOR")));
        if (partnerId != null) {
            b.claim(TokenClaims.PARTNER_ID_CLAIM, partnerId);
        }
        return b.build();
    }

    /** Clear the thread-bound context. Call from {@code @AfterEach}. */
    public static void clear() {
        SecurityContextHolder.clearContext();
    }

    private static List<SimpleGrantedAuthority> authorities(String... permissions) {
        List<SimpleGrantedAuthority> out = new ArrayList<>();
        for (String p : permissions) {
            out.add(new SimpleGrantedAuthority(TokenClaims.AUTHORITY_PREFIX + p));
        }
        return out;
    }
}
