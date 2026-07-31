package com.gme.pay.bff.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gme.pay.bff.client.ConfigRegistryClient;
import com.gme.pay.bff.client.PrefundingClient;
import com.gme.pay.bff.client.SettlementClient;
import com.gme.pay.bff.client.TransactionMgmtClient;
import com.gme.pay.bff.client.stub.StubApiKeyClient;
import com.gme.pay.bff.client.stub.StubConfigRegistryClient;
import com.gme.pay.bff.client.stub.StubPrefundingClient;
import com.gme.pay.bff.client.stub.StubSettlementClient;
import com.gme.pay.bff.client.stub.StubStatementClient;
import com.gme.pay.bff.client.stub.StubTransactionMgmtClient;
import com.gme.pay.bff.security.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Standalone MockMvc test for the Phase-C4 API keys endpoint on
 * {@link PartnerPortalController}. Uses the real {@link StubApiKeyClient}.
 */
class ApiKeyControllerTest {

    private MockMvc mvc;

    /**
     * Portal endpoints are tenant-scoped against the verified token (T0-4). These tests exercise
     * portal FUNCTIONALITY, so they authenticate as a platform operator holding the explicit
     * cross-partner read permission; the scope rules themselves are covered by
     * {@link PartnerPortalScopeTest}.
     */
    @BeforeEach
    void authenticateAsCrossReadingOperator() {
        TestTokens.hubOperator("partner.view");
    }

    @AfterEach
    void clearAuthentication() {
        TestTokens.clear();
    }

    @BeforeEach
    void setUp() {
        TransactionMgmtClient transactions = new StubTransactionMgmtClient();
        PrefundingClient prefunding = new StubPrefundingClient();
        SettlementClient settlement = new StubSettlementClient();
        ConfigRegistryClient configRegistry = new StubConfigRegistryClient();

        PartnerPortalController controller = new PartnerPortalController(
                transactions, prefunding, settlement, configRegistry,
                new StubApiKeyClient(),
                new com.gme.pay.bff.client.stub.StubSandboxKeyClient(),
                new StubStatementClient(),
                new com.gme.pay.bff.client.stub.StubPortalWebhookClient(),
                new OpsRbacGuard(true));

        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter(om);

        mvc = standaloneSetup(controller).setMessageConverters(converter).build();
    }

    @Test
    @DisplayName("Stub mode reports NO api keys — never fabricated gpk_live_ credentials")
    void apiKeys_stubModeReportsNoKeys() throws Exception {
        // Gap T1-3: StubApiKeyClient used to mint two credential-shaped rows per partner
        // (PRIMARY + ROTATING, gpk_live_<hash> prefixes, a two-scope grant list and a last-used
        // instant), all derived from partnerId.hashCode(). With no RestApiKeyClient in existence
        // that fiction was what every partner saw, indistinguishable from real credentials.
        // An unconfigured BFF must now answer "I know of no keys".
        mvc.perform(get("/v1/portal/{p}/api-keys", "partner_test_001"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("Stub mode invents no keys for any partner, including unknown ones")
    void apiKeys_stubModeIsEmptyForEveryPartner() throws Exception {
        for (String partner : new String[] {"partner_test_002", "GMEREMIT", "does-not-exist"}) {
            mvc.perform(get("/v1/portal/{p}/api-keys", partner))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
        }
    }

    @Test
    @DisplayName("The removed key fixtures cannot come back through the stub")
    void apiKeys_removedFixturesStayGone() {
        // Pin the regression directly at the client so a future edit to the stub cannot quietly
        // reintroduce credential-shaped data behind the controller.
        org.junit.jupiter.api.Assertions.assertTrue(
                new StubApiKeyClient().listForPartner("partner_test_001").isEmpty(),
                "StubApiKeyClient must report no keys rather than fabricate credentials");
    }
}
