package com.gme.pay.bff.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gme.pay.bff.client.ConfigRegistryClient;
import com.gme.pay.bff.client.PartnerDirectory;
import com.gme.pay.bff.client.WebhookOpsClient;
import com.gme.pay.bff.client.stub.StubOperatorActionAuditClient;
import com.gme.pay.bff.security.TestTokens;
import com.gme.pay.contracts.PartnerView;
import com.gme.pay.domain.PartnerType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Gap <b>T5-8</b>: the operator path into webhook endpoint secret rotation, which before this
 * existed nowhere — {@code POST /v1/webhooks/endpoints/{id}/rotate-secret} had no caller in any
 * service or SPA, so the endpoints T5-4 correctly refuses to sign for could not be revived.
 *
 * <p>Pinned here: the rotation is RBAC-guarded (403 without {@code ops:operate}) and audited
 * before it fires, the un-signable endpoints are reported, and a partner code that cannot be
 * resolved fails CLOSED instead of widening into a platform-wide read.
 */
class WebhookEndpointAdminControllerTest {

    private WebhookOpsClient webhooks;
    private StubOperatorActionAuditClient audit;
    private ConfigRegistryClient configRegistry;

    /** A pre-T5-4 endpoint: registered, active, and completely unable to sign. */
    private static final WebhookOpsClient.EndpointSigningHealth UNSIGNABLE =
            new WebhookOpsClient.EndpointSigningHealth(
                    "17", 42L, "LIVE", "https://legacy.example.com/hooks", 1,
                    "SECRET_NOT_DERIVABLE", false, true,
                    "the secret stored for this endpoint cannot be re-derived",
                    null, Instant.parse("2026-01-05T00:00:00Z"),
                    Instant.parse("2026-01-05T00:00:00Z"));

    @BeforeEach
    void setUp() {
        webhooks = mock(WebhookOpsClient.class);
        audit = new StubOperatorActionAuditClient();
        configRegistry = mock(ConfigRegistryClient.class);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** MockMvc over the controller with RBAC enforcement in the state the test needs. */
    private MockMvc mvc(boolean enforce) {
        // JavaTimeModule + ISO strings so Instant fields serialise the way the SPA reads them.
        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return MockMvcBuilders.standaloneSetup(new WebhookEndpointAdminController(
                        webhooks, audit, new PartnerDirectory(configRegistry),
                        new OpsRbacGuard(enforce)))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om))
                .build();
    }

    // ------------------------------------------------------------------ RBAC

    @Test
    @DisplayName("rotate is 403 for a token that lacks ops:operate — rotation invalidates a "
            + "secret a live partner integration is using")
    void rotate_forbiddenWithoutOpsPermission() throws Exception {
        TestTokens.hubOperator("partner.view", "txn.view", "report.generate");

        mvc(true).perform(post("/v1/admin/webhooks/endpoints/17/rotate-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"legacy endpoint cannot sign\"}"))
                .andExpect(status().isForbidden());

        verify(webhooks, never()).rotateEndpointSecret(any(), any());
        // Fail closed all the way: no audit row for an action that never happened.
        assertThat(audit.captured()).isEmpty();
    }

    @Test
    @DisplayName("rotate is 403 for a token with no permissions at all (fail closed)")
    void rotate_forbiddenForPermissionlessToken() throws Exception {
        TestTokens.hubOperator();

        mvc(true).perform(post("/v1/admin/webhooks/endpoints/17/rotate-secret"))
                .andExpect(status().isForbidden());

        verify(webhooks, never()).rotateEndpointSecret(any(), any());
    }

    @Test
    @DisplayName("rotate succeeds for ops:operate, reveals the secret once and audits WHO/WHAT/WHY")
    void rotate_authorizedRevealsSecretAndAudits() throws Exception {
        TestTokens.authenticate("ops.admin@gmepay.com", null, "ops:operate");
        when(webhooks.rotateEndpointSecret(eq("17"), eq(0L)))
                .thenReturn(new WebhookOpsClient.RotatedWebhookSecret(
                        "17", "whsec_rotated_fixture_value", 2,
                        null));

        mvc(true).perform(post("/v1/admin/webhooks/endpoints/17/rotate-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"pre-T5-4 endpoint could not sign\","
                                + "\"overlapMinutes\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endpointId").value("17"))
                .andExpect(jsonPath("$.signingSecretPlaintext").value("whsec_rotated_fixture_value"))
                .andExpect(jsonPath("$.secretGeneration").value(2));

        verify(webhooks).rotateEndpointSecret("17", 0L);
        assertThat(audit.captured()).singleElement().satisfies(rec -> {
            assertThat(rec.action()).isEqualTo("webhook.endpoint.rotate_secret");
            assertThat(rec.target()).isEqualTo("17");
            // The audited actor is the TOKEN subject, not anything the caller supplied.
            assertThat(rec.actor()).isEqualTo("ops.admin@gmepay.com");
            assertThat(rec.reason()).isEqualTo("pre-T5-4 endpoint could not sign");
            // The audit trail must never carry the secret.
            assertThat(rec.reason()).doesNotContain("whsec_");
        });
    }

    @Test
    @DisplayName("omitted overlapMinutes passes null so notification-webhook applies its default")
    void rotate_omittedOverlapDefersToUpstream() throws Exception {
        TestTokens.hubOperator("ops:operate");
        when(webhooks.rotateEndpointSecret(eq("17"), eq(null)))
                .thenReturn(new WebhookOpsClient.RotatedWebhookSecret(
                        "17", "whsec_x", 2, Instant.parse("2026-07-30T00:00:00Z")));

        mvc(true).perform(post("/v1/admin/webhooks/endpoints/17/rotate-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"scheduled\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previousSecretExpiresAt").exists());

        verify(webhooks).rotateEndpointSecret("17", null);
    }

    @Test
    @DisplayName("a non-numeric overlapMinutes is a 400, never a silent default")
    void rotate_rejectsNonNumericOverlap() throws Exception {
        TestTokens.hubOperator("ops:operate");

        mvc(true).perform(post("/v1/admin/webhooks/endpoints/17/rotate-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"overlapMinutes\":\"soon\"}"))
                .andExpect(status().isBadRequest());

        verify(webhooks, never()).rotateEndpointSecret(any(), any());
    }

    // ------------------------------------------------ signing-health report

    @Test
    @DisplayName("the report surfaces an endpoint that cannot sign, and says rotation fixes it")
    void signingHealth_reportsUnsignableEndpoint() throws Exception {
        TestTokens.hubOperator("partner.view");
        when(webhooks.endpointSigningHealth(null)).thenReturn(List.of(UNSIGNABLE));

        mvc(true).perform(get("/v1/admin/webhooks/endpoints"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].endpointId").value("17"))
                .andExpect(jsonPath("$[0].status").value("SECRET_NOT_DERIVABLE"))
                .andExpect(jsonPath("$[0].deliverable").value(false))
                .andExpect(jsonPath("$[0].fixableByRotation").value(true));
    }

    @Test
    @DisplayName("partnerCode is resolved to the numeric surrogate the upstream keys endpoints by")
    void signingHealth_resolvesPartnerCode() throws Exception {
        TestTokens.hubOperator("partner.view");
        when(configRegistry.getPartnerView("GMEREMIT")).thenReturn(partnerView(42L));
        when(webhooks.endpointSigningHealth(42L)).thenReturn(List.of(UNSIGNABLE));

        mvc(true).perform(get("/v1/admin/webhooks/endpoints").param("partnerCode", "GMEREMIT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].partnerId").value(42));

        verify(webhooks).endpointSigningHealth(42L);
    }

    @Test
    @DisplayName("an unresolvable partnerCode returns NOTHING — it must never widen into the "
            + "platform-wide sweep")
    void signingHealth_unresolvablePartnerFailsClosed() throws Exception {
        TestTokens.hubOperator("partner.view");
        when(configRegistry.getPartnerView("NOPE")).thenReturn(null);

        mvc(true).perform(get("/v1/admin/webhooks/endpoints").param("partnerCode", "NOPE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        verify(webhooks, never()).endpointSigningHealth(any());
    }

    private static PartnerView partnerView(Long id) {
        return PartnerView.ofCore(id, "GMEREMIT", PartnerType.OVERSEAS, "KRW", RoundingMode.HALF_UP);
    }
}
