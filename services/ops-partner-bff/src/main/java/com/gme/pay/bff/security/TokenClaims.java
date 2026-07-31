package com.gme.pay.bff.security;

import com.gme.pay.rbac.PermissionContext;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Resolves the calling identity for the current request <b>from the verified access token</b>
 * — never from a client-supplied header.
 *
 * <h2>Why this class exists (T0-3 / T0-4)</h2>
 * <p>Before this, {@code OpsRbacGuard} read the raw {@code X-Gme-Permissions} request header
 * and {@code PartnerPortalController} trusted the {@code {partnerId}} path segment, so any
 * caller could self-authorize (<code>curl -H 'X-Gme-Permissions: ops:operate'</code>) and read
 * any other partner's data. The {@code X-Gme-*} headers are only trustworthy when an upstream
 * edge stamps AND signs them ({@link com.gme.pay.rbac.RbacClaimSigner}); the BFF is called
 * directly by the two SPAs with no such edge in front of it, so <b>the only trustworthy source
 * of identity here is the JWT that {@link com.gme.pay.bff.config.BffSecurityConfig} verified.</b>
 *
 * <p>This resolver reads the {@link SecurityContextHolder} — populated per request by the
 * Spring Security filter chain after signature/issuer/expiry validation — and projects it onto
 * the shared {@link PermissionContext} record from {@code lib-rbac}, so downstream authorization
 * code (see {@link com.gme.pay.bff.web.OpsRbacGuard}) speaks the same vocabulary as the rest of
 * the platform.
 *
 * <h2>Claim contract</h2>
 * <ul>
 *   <li>{@code sub} → {@link PermissionContext#principalId()}</li>
 *   <li>{@value #PARTNER_ID_CLAIM} (fallbacks {@value #TENANT_ID_CLAIM}, {@value #PARTNER_ID_CLAIM_CAMEL})
 *       → {@link PermissionContext#tenantId()}; absent/blank ⇒ a platform-hub operator, i.e.
 *       <em>not</em> scoped to any single partner</li>
 *   <li>{@value #PERMISSIONS_CLAIM} — JSON array or comma-separated string of permission codes
 *       ({@code resource.action}) → {@link PermissionContext#permissions()}</li>
 *   <li>{@code realm_access.roles[]} (Keycloak) → {@link PermissionContext#roles()}</li>
 * </ul>
 *
 * <p>The IdP is responsible for populating {@value #PERMISSIONS_CLAIM} (Keycloak: a
 * "User Attribute"/"Hardcoded claim" protocol mapper, or a token-exchange step that copies
 * auth-identity's {@code /v1/rbac/resolve} output). A token with no permissions claim yields an
 * authenticated principal with an EMPTY permission set — which every {@code require*} check in
 * {@link com.gme.pay.bff.web.OpsRbacGuard} denies. Authenticated is not authorized.
 *
 * <p>For non-JWT {@link Authentication}s (used by tests and by any future mTLS/opaque-token
 * path) permissions are read from Spring authorities carrying the {@value #AUTHORITY_PREFIX}
 * prefix, which is exactly what {@code BffSecurityConfig}'s converter stamps.
 */
public final class TokenClaims {

    /** Preferred claim carrying the partner (tenant) the token is scoped to. */
    public static final String PARTNER_ID_CLAIM = "partner_id";
    /** Fallback partner claim name — matches {@link com.gme.pay.rbac.RbacHeaders#TENANT_ID}'s vocabulary. */
    public static final String TENANT_ID_CLAIM = "tenant_id";
    /** Fallback partner claim name for IdPs that emit camelCase. */
    public static final String PARTNER_ID_CLAIM_CAMEL = "partnerId";
    /** Claim carrying the effective permission codes (array or comma-separated string). */
    public static final String PERMISSIONS_CLAIM = "permissions";
    /** Keycloak claim wrapping realm-level roles. */
    public static final String REALM_ACCESS_CLAIM = "realm_access";
    /** Inner claim under {@link #REALM_ACCESS_CLAIM} listing role names. */
    public static final String ROLES_CLAIM = "roles";
    /** Spring authority prefix under which a permission code is exposed as a {@link GrantedAuthority}. */
    public static final String AUTHORITY_PREFIX = "PERM_";
    /** Spring authority prefix for realm roles (mirrors api-gateway's {@code SecurityConfig}). */
    public static final String ROLE_PREFIX = "ROLE_";

    private TokenClaims() {}

    /**
     * The current request's verified identity, or {@link PermissionContext#ANONYMOUS} when no
     * authentication is present (anonymous / unauthenticated / outside a request).
     */
    public static PermissionContext current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return PermissionContext.ANONYMOUS;
        }
        if (auth.getPrincipal() instanceof Jwt jwt) {
            return fromJwt(jwt);
        }
        return new PermissionContext(
                auth.getName(),
                null,
                stripPrefix(auth.getAuthorities(), AUTHORITY_PREFIX),
                stripPrefix(auth.getAuthorities(), ROLE_PREFIX));
    }

    /** Projects a verified {@link Jwt} onto a {@link PermissionContext}. */
    public static PermissionContext fromJwt(Jwt jwt) {
        return new PermissionContext(
                jwt.getSubject(),
                partnerIdOf(jwt),
                permissionsOf(jwt),
                realmRolesOf(jwt));
    }

    /** True when the request carries a verified identity. */
    public static boolean isAuthenticated() {
        return current().isAuthenticated();
    }

    /** Subject of the verified token, or {@code null} when unauthenticated. */
    public static String principalId() {
        return current().principalId();
    }

    /**
     * The partner this token is scoped to, or {@code null} for a platform-hub operator token
     * (no partner claim). Never derived from a request header or path segment.
     */
    public static String partnerId() {
        return current().tenantId();
    }

    /** Effective permission codes from the verified token (possibly empty, never null). */
    public static Set<String> permissions() {
        return current().permissions();
    }

    // -------- claim extraction --------------------------------------------------

    /** First non-blank of {@link #PARTNER_ID_CLAIM}, {@link #TENANT_ID_CLAIM}, {@link #PARTNER_ID_CLAIM_CAMEL}. */
    public static String partnerIdOf(Jwt jwt) {
        for (String name : new String[] {PARTNER_ID_CLAIM, TENANT_ID_CLAIM, PARTNER_ID_CLAIM_CAMEL}) {
            Object v = jwt.getClaims().get(name);
            if (v instanceof String s && !s.isBlank()) {
                return s.trim();
            }
        }
        return null;
    }

    /**
     * Permission codes from {@link #PERMISSIONS_CLAIM}. Accepts a JSON array of strings or a
     * single comma-separated string (both shapes occur across IdPs / protocol mappers).
     */
    public static Set<String> permissionsOf(Jwt jwt) {
        return codes(jwt.getClaims().get(PERMISSIONS_CLAIM));
    }

    /** Keycloak realm role names, tolerating a missing or malformed {@code realm_access} claim. */
    public static Set<String> realmRolesOf(Jwt jwt) {
        Object realmAccess = jwt.getClaims().get(REALM_ACCESS_CLAIM);
        if (!(realmAccess instanceof Map<?, ?> realmMap)) {
            return Set.of();
        }
        return codes(realmMap.get(ROLES_CLAIM));
    }

    private static Set<String> codes(Object claim) {
        Set<String> out = new LinkedHashSet<>();
        if (claim instanceof String s) {
            Arrays.stream(s.split(","))
                    .map(String::trim)
                    .filter(t -> !t.isEmpty())
                    .forEach(out::add);
        } else if (claim instanceof Collection<?> coll) {
            coll.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .map(String::trim)
                    .filter(t -> !t.isEmpty())
                    .forEach(out::add);
        }
        return Set.copyOf(out);
    }

    private static Set<String> stripPrefix(Collection<? extends GrantedAuthority> authorities, String prefix) {
        if (authorities == null) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (GrantedAuthority a : authorities) {
            String v = a.getAuthority();
            if (v != null && v.startsWith(prefix) && v.length() > prefix.length()) {
                out.add(v.substring(prefix.length()));
            }
        }
        return Set.copyOf(out);
    }
}
