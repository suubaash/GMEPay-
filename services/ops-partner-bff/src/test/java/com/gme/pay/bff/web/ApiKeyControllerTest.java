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

    @BeforeEach
    void setUp() {
        TransactionMgmtClient transactions = new StubTransactionMgmtClient();
        PrefundingClient prefunding = new StubPrefundingClient();
        SettlementClient settlement = new StubSettlementClient();
        ConfigRegistryClient configRegistry = new StubConfigRegistryClient();

        PartnerPortalController controller = new PartnerPortalController(
                transactions, prefunding, settlement, configRegistry,
                new StubApiKeyClient(), new StubStatementClient());

        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter(om);

        mvc = standaloneSetup(controller).setMessageConverters(converter).build();
    }

    @Test
    @DisplayName("GET /v1/portal/{p}/api-keys returns one PRIMARY and one ROTATING key")
    void apiKeys_returnsPrimaryAndRotating() throws Exception {
        mvc.perform(get("/v1/portal/{p}/api-keys", "partner_test_001"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].status").value("PRIMARY"))
                .andExpect(jsonPath("$[1].status").value("ROTATING"))
                .andExpect(jsonPath("$[0].prefix").value(org.hamcrest.Matchers.startsWith("gpk_live_")))
                .andExpect(jsonPath("$[1].prefix").value(org.hamcrest.Matchers.startsWith("gpk_live_")))
                .andExpect(jsonPath("$[0].scopes.length()").value(2));
    }

    @Test
    @DisplayName("GET /v1/portal/{p}/api-keys returns deterministic keys per partner")
    void apiKeys_isDeterministicPerPartner() throws Exception {
        // Two calls with the same partner should return identical keyIds.
        mvc.perform(get("/v1/portal/{p}/api-keys", "partner_test_002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].keyId").value("key_primary_partner_test_002"))
                .andExpect(jsonPath("$[1].keyId").value("key_rotating_partner_test_002"));
    }
}
