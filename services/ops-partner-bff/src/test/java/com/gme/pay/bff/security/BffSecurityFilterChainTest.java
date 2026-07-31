package com.gme.pay.bff.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end identity-boundary test through the REAL Spring Security filter chain, the REAL MVC
 * mappings and the REAL {@link com.gme.pay.bff.config.AdminSurfaceRbacInterceptor}
 * (gap register T0-1 / T0-2 / T0-3 / T0-4).
 *
 * <p>Closes the audit's "Done when" for the BFF: an unauthenticated request to its most privileged
 * endpoints returns 401; a valid but unprivileged token gets 403; the deleted {@code /v1/auth/login}
 * stub is gone; and forged {@code X-Gme-*} headers buy nothing.
 *
 * <p>The {@link JwtDecoder} is stubbed so no IdP is needed — the token STRING encodes the identity
 * as {@code sub|partnerId|perm,perm} ({@code -} = absent). That substitutes only the cryptographic
 * verification step; every authorization decision under test is the production code path.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        // This class tests the SECURITY filter chain (who may reach which route), not upstream
        // wiring, so it opts into the in-memory clients explicitly. That opt-in is now REQUIRED
        // and that is the point of the change that added it: the shipped selectors were inverted
        // so the LIVE clients win when nobody chooses, because a stub winning by default is how
        // three surfaces ended up serving fabricated data in production. Without these lines the
        // 200-path assertions here would open real sockets to config-registry / transaction-mgmt.
        "gmepay.config-registry.client=stub",
        "gmepay.transaction-mgmt.client=stub",
        "gmepay.prefunding.client=stub",
        "gmepay.notification-webhook.client=stub",
        "gmepay.settlement-reconciliation.client=stub",
        "gmepay.revenue-ledger.client=stub",
        "gmepay.reporting-compliance.client=stub",
        "gmepay.auth-identity.client=stub",
        "gmepay.system-health.client=stub",
        "gmepay.ops-control.client=stub",
        "gmepay.webhook-ops.client=stub"})
@AutoConfigureMockMvc
class BffSecurityFilterChainTest {

    /** A token value the stub decoder rejects, standing in for an expired/forged/wrong-issuer JWT. */
    private static final String INVALID_TOKEN = "not-a-valid-token";

    @Autowired
    MockMvc mvc;

    /**
     * Replaces the issuer-derived decoder with one that decodes {@code sub|partnerId|perms} out of a
     * base64url token value, so the test needs no Keycloak. Anything else is refused with
     * {@link BadJwtException} — the same failure mode as a forged/expired/wrong-issuer token, which
     * the resource-server filter turns into 401.
     *
     * <p>Token values are base64url because Spring's {@code DefaultBearerTokenResolver} only accepts
     * the RFC 6750 {@code b64token} character set in an {@code Authorization: Bearer} header.
     */
    @TestConfiguration
    static class StubDecoderConfig {
        @Bean
        @Primary
        JwtDecoder jwtDecoder() {
            return token -> {
                String spec;
                try {
                    spec = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
                } catch (IllegalArgumentException e) {
                    throw new BadJwtException("stub decoder rejects: " + token, e);
                }
                String[] parts = spec.split("\\|", -1);
                if (parts.length != 3) {
                    throw new BadJwtException("stub decoder rejects: " + token);
                }
                String partnerId = "-".equals(parts[1]) ? null : parts[1];
                String[] perms = parts[2].isBlank() ? new String[0] : parts[2].split(",");
                return TestTokens.jwt(parts[0], partnerId, perms);
            };
        }
    }

    private static String bearer(String subject, String partnerId, String permissions) {
        String spec = subject + "|" + (partnerId == null ? "-" : partnerId) + "|" + permissions;
        return "Bearer " + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(spec.getBytes(StandardCharsets.UTF_8));
    }

    // ---------- 401: no verified identity -------------------------------------------------

    @Test
    @DisplayName("unauthenticated request to the admin surface is 401")
    void unauthenticated_adminIs401() throws Exception {
        mvc.perform(get("/v1/admin/partners")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/admin/dashboard")).andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/admin/ops/pause")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("unauthenticated request to the partner portal is 401")
    void unauthenticated_portalIs401() throws Exception {
        mvc.perform(get("/v1/portal/partner_test_001/balance")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a forged X-Gme-Permissions header does not authenticate anything (still 401)")
    void forgedRbacHeaders_are401() throws Exception {
        mvc.perform(post("/v1/admin/ops/pause")
                        .header("X-Gme-Permissions", "ops:operate")
                        .header("X-Gme-Principal-Id", "attacker")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an invalid / unverifiable bearer token is 401")
    void invalidToken_is401() throws Exception {
        mvc.perform(get("/v1/admin/partners").header("Authorization", "Bearer " + INVALID_TOKEN))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the password=demo dev-login endpoint no longer exists")
    void devLoginEndpointIsGone() throws Exception {
        // Unauthenticated it is indistinguishable from any other protected path (401)…
        mvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"anyone\",\"password\":\"demo\"}"))
                .andExpect(status().isUnauthorized());
        // …and with a valid token there is no such handler at all: it can never mint a token again.
        mvc.perform(post("/v1/auth/login")
                        .header("Authorization", bearer("op", null, "ops:operate"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"anyone\",\"password\":\"demo\"}"))
                .andExpect(status().isNotFound());
    }

    // ---------- actuator health stays anonymous -------------------------------------------

    @Test
    @DisplayName("only /actuator/health** is anonymous; other actuator + API docs are protected")
    void healthIsPublic_othersAreNot() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
    }

    // ---------- 403: authenticated but not authorized --------------------------------------

    @Test
    @DisplayName("admin READ without any hub permission is 403; with partner.view it is 200")
    void adminRead_requiresHubPermission() throws Exception {
        mvc.perform(get("/v1/admin/partners")
                        .header("Authorization", bearer("nobody", null, "")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/v1/admin/partners")
                        .header("Authorization", bearer("operator", null, "partner.view")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a partner-scoped token cannot read the admin surface at all")
    void partnerToken_cannotReachAdmin() throws Exception {
        mvc.perform(get("/v1/admin/partners")
                        .header("Authorization", bearer("partner-user", "partner_test_001", "")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("admin WRITE with only read permissions is 403; with ops:operate it is 200")
    void adminWrite_requiresWritePermission() throws Exception {
        mvc.perform(post("/v1/admin/ops/pause")
                        .header("Authorization", bearer("readonly", null, "partner.view,txn.view,report.generate"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/v1/admin/ops/pause")
                        .header("Authorization", bearer("operator", null, "ops:operate"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("partner-onboarding writes (KYB screening) are no longer open")
    void onboardingWrite_isGuarded() throws Exception {
        mvc.perform(post("/v1/admin/partners/partner_test_001/kyb/screen")
                        .header("Authorization", bearer("readonly", null, "partner.view"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    // ---------- T0-4: cross-partner IDOR ---------------------------------------------------

    @Test
    @DisplayName("a partner token reading ITS OWN portal data is 200")
    void portal_ownDataIs200() throws Exception {
        mvc.perform(get("/v1/portal/partner_test_001/balance")
                        .header("Authorization", bearer("user@a", "partner_test_001", "")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a partner token reading ANOTHER partner's portal data is 403")
    void portal_otherPartnerIs403() throws Exception {
        mvc.perform(get("/v1/portal/partner_test_002/balance")
                        .header("Authorization", bearer("user@a", "partner_test_001", "")))
                .andExpect(status().isForbidden());
        // …and it cannot buy the cross-read by claiming the permission on the wire.
        mvc.perform(get("/v1/portal/partner_test_002/balance")
                        .header("Authorization", bearer("user@a", "partner_test_001", ""))
                        .header("X-Gme-Permissions", "partner.view")
                        .header("X-Partner-Id", "partner_test_002"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a partner token cannot mint sandbox keys in another partner's name")
    void portal_crossPartnerWriteIs403() throws Exception {
        mvc.perform(post("/v1/portal/partner_test_002/sandbox-keys")
                        .header("Authorization", bearer("user@a", "partner_test_001", ""))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"mine\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an operator holding partner.view may cross-read a partner's portal view")
    void portal_operatorCrossReadIs200() throws Exception {
        mvc.perform(get("/v1/portal/partner_test_001/balance")
                        .header("Authorization", bearer("support-agent", null, "partner.view")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a hub token WITHOUT the cross-read permission is 403 on the portal")
    void portal_hubTokenWithoutPermissionIs403() throws Exception {
        mvc.perform(get("/v1/portal/partner_test_001/balance")
                        .header("Authorization", bearer("nobody", null, "report.generate")))
                .andExpect(status().isForbidden());
    }
}
