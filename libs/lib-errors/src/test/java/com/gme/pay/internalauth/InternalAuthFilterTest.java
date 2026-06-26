package com.gme.pay.internalauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The service-to-service internal-auth gate (#90) — the perimeter that stops a direct network caller
 * from reaching auth-identity's internal-only {@code /v1/rbac/**} ({@code /resolve} + management) and
 * {@code /v1/approvals/**}. Path-matching + constant-time secret match are exercised directly; the
 * admit-vs-401 behaviour through {@code doFilter} uses Spring's servlet mocks.
 */
class InternalAuthFilterTest {

    private static final String SECRET = "internal-svc-secret";
    private static final List<String> PATTERNS = List.of("/v1/rbac/**", "/v1/approvals/**");

    private final InternalAuthFilter filter = new InternalAuthFilter(SECRET, PATTERNS);

    // ----- path matching -----

    @Test
    @DisplayName("internal-only paths are protected; the public surface is not")
    void pathMatching() {
        assertTrue(filter.isProtected("/v1/rbac/resolve"));
        assertTrue(filter.isProtected("/v1/rbac/roles"));
        assertTrue(filter.isProtected("/v1/rbac/principals/5/permissions"));
        assertTrue(filter.isProtected("/v1/approvals/7/approve"));
        assertFalse(filter.isProtected("/actuator/health"));
        assertFalse(filter.isProtected("/v1/auth/token"));
        assertFalse(filter.isProtected("/swagger-ui.html"));
        assertFalse(filter.isProtected(null));
    }

    @Test
    @DisplayName("the DEFAULT patterns cover all of auth-identity's internal-only surface (incl. /internal/auth/**)")
    void defaultPatternsCoverInternalSurface() {
        InternalAuthFilter f = new InternalAuthFilter(SECRET, new InternalAuthProperties().getPathPatterns());
        assertTrue(f.isProtected("/v1/rbac/resolve"));
        assertTrue(f.isProtected("/v1/approvals/7/approve"));
        assertTrue(f.isProtected("/internal/auth/keys"));            // API-key issuance
        assertTrue(f.isProtected("/internal/auth/keys/abc/revoke"));
        assertTrue(f.isProtected("/internal/auth/verify"));          // HMAC verify
        assertFalse(f.isProtected("/v1/auth/token"));                // public auth surface untouched
        assertFalse(f.isProtected("/actuator/health"));
    }

    // ----- secret comparison -----

    @Test
    @DisplayName("only the exact secret matches; null/blank/wrong are rejected")
    void secretMatching() {
        assertTrue(filter.secretMatches(SECRET));
        assertTrue(filter.secretMatches("  " + SECRET + "  "));   // trimmed
        assertFalse(filter.secretMatches(null));
        assertFalse(filter.secretMatches(""));
        assertFalse(filter.secretMatches("   "));
        assertFalse(filter.secretMatches("wrong"));
        assertFalse(filter.secretMatches(SECRET + "x"));
    }

    // ----- end-to-end filter behaviour -----

    @Test
    @DisplayName("a protected path with no token is refused 401 and the chain is NOT invoked")
    void protectedWithoutTokenRefused() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/rbac/resolve");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        assertEquals(401, resp.getStatus());
        assertNull(chain.getRequest(), "downstream must not run when the token is missing");
        assertTrue(resp.getContentAsString().contains("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("a protected path with a forged token is refused 401")
    void protectedWithForgedTokenRefused() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/approvals/7/approve");
        req.addHeader(InternalAuthHeaders.INTERNAL_TOKEN, "not-the-secret");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        assertEquals(401, resp.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    @DisplayName("a protected path with the correct token passes through to the chain")
    void protectedWithCorrectTokenPasses() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/rbac/roles");
        req.addHeader(InternalAuthHeaders.INTERNAL_TOKEN, SECRET);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        assertEquals(req, chain.getRequest(), "a trusted internal caller must reach the controller");
        assertEquals(200, resp.getStatus());
    }

    @Test
    @DisplayName("an un-protected path passes through even without a token")
    void unprotectedPathPasses() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        assertEquals(req, chain.getRequest());
    }
}
