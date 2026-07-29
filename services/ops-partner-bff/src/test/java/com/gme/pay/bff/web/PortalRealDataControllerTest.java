package com.gme.pay.bff.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gme.pay.bff.client.ConfigRegistryClient;
import com.gme.pay.bff.client.PortalWebhookClient;
import com.gme.pay.bff.client.PrefundingClient;
import com.gme.pay.bff.client.SettlementClient;
import com.gme.pay.bff.client.TransactionMgmtClient;
import com.gme.pay.bff.client.stub.StubApiKeyClient;
import com.gme.pay.bff.client.stub.StubPortalWebhookClient;
import com.gme.pay.bff.client.stub.StubPrefundingClient;
import com.gme.pay.bff.client.stub.StubSandboxKeyClient;
import com.gme.pay.bff.client.stub.StubSettlementClient;
import com.gme.pay.bff.client.stub.StubStatementClient;
import com.gme.pay.bff.client.stub.StubTransactionMgmtClient;
import com.gme.pay.bff.security.TestTokens;
import com.gme.pay.bff.web.dto.WebhookConfigView;
import com.gme.pay.contracts.PartnerStatus;
import com.gme.pay.contracts.PartnerView;
import com.gme.pay.domain.PartnerType;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Gap T1-3: the two portal pages that were pure fixtures inside the controller itself — Webhooks
 * (two inline {@code partner.example.com} rows) and Profile (a constant {@code onboardedAt} shared
 * by every partner).
 *
 * <p>Every request here authenticates as the PARTNER'S OWN token, so it goes through
 * {@link OpsRbacGuard#requirePartnerScope(String)} exactly as production does — no operator
 * cross-read shortcut, no security bypass.
 */
class PortalRealDataControllerTest {

    private static final String PARTNER = "GMEREMIT";
    private static final Instant WENT_LIVE = Instant.parse("2026-03-17T04:20:00Z");

    private MockMvc mvc;

    @AfterEach
    void clearAuthentication() {
        TestTokens.clear();
    }

    /** A partner-scoped token for {@link #PARTNER} — the real portal identity path. */
    private void authenticateAsPartner() {
        TestTokens.partner(PARTNER);
    }

    /**
     * Builds the controller around the supplied webhook + registry seams; every other client is the
     * standard stub.
     */
    private MockMvc build(PortalWebhookClient webhooks, ConfigRegistryClient registry) {
        TransactionMgmtClient transactions = new StubTransactionMgmtClient();
        PrefundingClient prefunding = new StubPrefundingClient();
        SettlementClient settlement = new StubSettlementClient();

        PartnerPortalController controller = new PartnerPortalController(
                transactions, prefunding, settlement, registry,
                new StubApiKeyClient(), new StubSandboxKeyClient(), new StubStatementClient(),
                webhooks, new OpsRbacGuard(true));

        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return standaloneSetup(controller)
                .setMessageConverters(
                        new org.springframework.http.converter.json
                                .MappingJackson2HttpMessageConverter(om))
                .build();
    }

    /** Registry whose canonical view carries a real {@code goLiveAt}. */
    private static ConfigRegistryClient registryWithGoLiveAt(Instant goLiveAt,
                                                             PartnerStatus status) {
        return new ConfigRegistryClient() {
            @Override
            public PartnerSummary getPartner(String partnerId) {
                return PARTNER.equals(partnerId)
                        ? new PartnerSummary(PARTNER, "OVERSEAS", "USD", RoundingMode.HALF_UP)
                        : null;
            }

            @Override
            public PartnerView getPartnerView(String partnerCode) {
                if (!PARTNER.equals(partnerCode)) {
                    return null;
                }
                return new PartnerView(
                        4242L, PARTNER, PartnerType.OVERSEAS, "USD", RoundingMode.HALF_UP,
                        "USD", "USD", null, "GME Remit Co., Ltd.", null, null, "KR", null,
                        null, null, null, status,
                        Instant.parse("2026-07-01T00:00:00Z"), null,
                        Instant.parse("2026-07-01T00:00:00Z"),
                        goLiveAt);
            }

            @Override
            public List<PartnerSummary> listPartners() {
                return List.of();
            }

            @Override
            public PartnerSummary createPartner(PartnerCreateRequest request) {
                return null;
            }

            @Override
            public PartnerSummary updateRoundingMode(String partnerId, String mode) {
                return null;
            }

            @Override
            public List<SchemeSummary> listSchemes() {
                return List.of();
            }
        };
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Webhooks
    // ──────────────────────────────────────────────────────────────────────────

    @BeforeEach
    void authenticate() {
        authenticateAsPartner();
    }

    @Test
    @DisplayName("GET webhooks returns the partner's REAL endpoints from notification-webhook")
    void webhooks_returnsRealEndpoints() throws Exception {
        PortalWebhookClient real = partnerCode -> List.of(
                new WebhookConfigView("https://ops.gmeremit.com/gmepay/payments",
                        List.of("payment.approved"), "ACTIVE", null));
        mvc = build(real, registryWithGoLiveAt(WENT_LIVE, PartnerStatus.LIVE));

        mvc.perform(get("/v1/portal/{p}/webhooks", PARTNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].url").value("https://ops.gmeremit.com/gmepay/payments"))
                .andExpect(jsonPath("$[0].eventTypes[0]").value("payment.approved"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                // No real source for a per-endpoint last delivery -> absent, not a literal Instant.
                .andExpect(jsonPath("$[0].lastDeliveredAt").doesNotExist());
    }

    @Test
    @DisplayName("GET webhooks no longer serves the inline partner.example.com fixtures")
    void webhooks_fixturesAreGone() throws Exception {
        // Stub mode = the BFF is not connected to notification-webhook, so it knows of no
        // endpoints. Previously this handler manufactured two rows regardless.
        mvc = build(new StubPortalWebhookClient(),
                registryWithGoLiveAt(WENT_LIVE, PartnerStatus.LIVE));

        String body = mvc.perform(get("/v1/portal/{p}/webhooks", PARTNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0))
                .andReturn().getResponse().getContentAsString();

        org.junit.jupiter.api.Assertions.assertFalse(body.contains("partner.example.com"),
                "the removed inline webhook fixture leaked back: " + body);
    }

    @Test
    @DisplayName("GET webhooks tolerates a client returning null (empty array, not 500)")
    void webhooks_nullDegradesToEmptyArray() throws Exception {
        mvc = build(partnerCode -> null, registryWithGoLiveAt(WENT_LIVE, PartnerStatus.LIVE));

        mvc.perform(get("/v1/portal/{p}/webhooks", PARTNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("a partner's token cannot read another partner's webhooks")
    void webhooks_areTenantScoped() throws Exception {
        mvc = build(partnerCode -> List.of(
                        new WebhookConfigView("https://someone-else/hook", List.of(), "ACTIVE", null)),
                registryWithGoLiveAt(WENT_LIVE, PartnerStatus.LIVE));

        // GMEREMIT's token asking for SENDMN's webhooks: denied by requirePartnerScope.
        mvc.perform(get("/v1/portal/{p}/webhooks", "SENDMN"))
                .andExpect(status().isForbidden());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Profile — onboardedAt
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET profile reports the REAL activation instant as onboardedAt")
    void profile_onboardedAtIsTheRealGoLiveAt() throws Exception {
        mvc = build(new StubPortalWebhookClient(),
                registryWithGoLiveAt(WENT_LIVE, PartnerStatus.LIVE));

        mvc.perform(get("/v1/portal/{p}/profile", PARTNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partnerId").value(PARTNER))
                .andExpect(jsonPath("$.type").value("OVERSEAS"))
                .andExpect(jsonPath("$.settlementCurrency").value("USD"))
                // V025 partners.go_live_at, NOT the old constant 2026-01-01T00:00:00Z.
                .andExpect(jsonPath("$.onboardedAt").value("2026-03-17T04:20:00Z"));
    }

    @Test
    @DisplayName("a partner that has never gone live reports NO onboardedAt")
    void profile_notYetLiveHasNoOnboardedAt() throws Exception {
        // go_live_at is null until the first UAT -> LIVE transition. That must surface as absent,
        // NOT be back-filled from validFrom/recordedAt (which move on every registry edit and
        // would read as a plausible but wrong onboarding date).
        mvc = build(new StubPortalWebhookClient(),
                registryWithGoLiveAt(null, PartnerStatus.SANDBOX));

        mvc.perform(get("/v1/portal/{p}/profile", PARTNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partnerId").value(PARTNER))
                .andExpect(jsonPath("$.onboardedAt").doesNotExist());
    }

    @Test
    @DisplayName("the old constant onboardedAt is gone for every partner")
    void profile_constantOnboardedAtIsGone() throws Exception {
        mvc = build(new StubPortalWebhookClient(),
                registryWithGoLiveAt(null, PartnerStatus.SANDBOX));

        String body = mvc.perform(get("/v1/portal/{p}/profile", PARTNER))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.junit.jupiter.api.Assertions.assertFalse(body.contains("2026-01-01T00:00:00Z"),
                "the removed constant onboardedAt leaked back: " + body);
    }

    @Test
    @DisplayName("a registry serving only the legacy summary yields a real profile with NO onboardedAt")
    void profile_legacySummaryFallbackOmitsOnboardedAt() throws Exception {
        ConfigRegistryClient legacyOnly = new ConfigRegistryClient() {
            @Override
            public PartnerSummary getPartner(String partnerId) {
                return PARTNER.equals(partnerId)
                        ? new PartnerSummary(PARTNER, "OVERSEAS", "USD", RoundingMode.HALF_UP)
                        : null;
            }

            @Override
            public List<PartnerSummary> listPartners() {
                return List.of();
            }

            @Override
            public PartnerSummary createPartner(PartnerCreateRequest request) {
                return null;
            }

            @Override
            public PartnerSummary updateRoundingMode(String partnerId, String mode) {
                return null;
            }

            @Override
            public List<SchemeSummary> listSchemes() {
                return List.of();
            }
        };
        mvc = build(new StubPortalWebhookClient(), legacyOnly);

        mvc.perform(get("/v1/portal/{p}/profile", PARTNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partnerId").value(PARTNER))
                .andExpect(jsonPath("$.settlementRoundingMode").value("HALF_UP"))
                .andExpect(jsonPath("$.onboardedAt").doesNotExist());
    }

    @Test
    @DisplayName("GET profile 404s for a partner config-registry does not know")
    void profile_unknownPartnerIs404() throws Exception {
        // Authenticate as the unknown partner so the scope guard passes and the 404 is the
        // registry's answer, not an authorization artefact.
        TestTokens.clear();
        TestTokens.partner("GHOST");
        mvc = build(new StubPortalWebhookClient(),
                registryWithGoLiveAt(WENT_LIVE, PartnerStatus.LIVE));

        mvc.perform(get("/v1/portal/{p}/profile", "GHOST"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a partner's token cannot read another partner's profile")
    void profile_isTenantScoped() throws Exception {
        mvc = build(new StubPortalWebhookClient(),
                registryWithGoLiveAt(WENT_LIVE, PartnerStatus.LIVE));

        mvc.perform(get("/v1/portal/{p}/profile", "SENDMN"))
                .andExpect(status().isForbidden());
    }
}
