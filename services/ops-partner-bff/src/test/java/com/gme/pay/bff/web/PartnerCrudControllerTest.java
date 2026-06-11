package com.gme.pay.bff.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gme.pay.bff.client.ConfigRegistryClient;
import com.gme.pay.bff.client.PrefundingClient;
import com.gme.pay.bff.client.RevenueLedgerClient;
import com.gme.pay.bff.client.SettlementClient;
import com.gme.pay.bff.client.TransactionMgmtClient;
import com.gme.pay.bff.client.stub.StubConfigRegistryClient;
import com.gme.pay.bff.client.stub.StubPrefundingClient;
import com.gme.pay.bff.client.stub.StubRevenueLedgerClient;
import com.gme.pay.bff.client.stub.StubSettlementClient;
import com.gme.pay.bff.client.stub.StubTransactionMgmtClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Standalone MockMvc test for the Phase-C2 partner CRUD endpoints on
 * {@link AdminDashboardController}. Uses the real stub clients so the round-trip
 * mutation (create / update rounding mode) is exercised end-to-end.
 */
class PartnerCrudControllerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ConfigRegistryClient configRegistry = new StubConfigRegistryClient();
        TransactionMgmtClient transactions = new StubTransactionMgmtClient();
        PrefundingClient prefunding = new StubPrefundingClient();
        RevenueLedgerClient revenue = new StubRevenueLedgerClient();
        SettlementClient settlement = new StubSettlementClient();

        AdminDashboardController controller = new AdminDashboardController(
                configRegistry, transactions, prefunding, revenue, settlement);

        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter(om);

        mvc = standaloneSetup(controller).setMessageConverters(converter).build();
    }

    @Test
    @DisplayName("POST /v1/admin/partners creates a partner and returns 201 with the summary")
    void createPartner_returns201() throws Exception {
        String body = """
                {"partnerId":"partner_new_001","type":"OVERSEAS","settlementCurrency":"EUR","settlementRoundingMode":"DOWN"}
                """;
        mvc.perform(post("/v1/admin/partners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.partnerId").value("partner_new_001"))
                .andExpect(jsonPath("$.type").value("OVERSEAS"))
                .andExpect(jsonPath("$.settlementCurrency").value("EUR"))
                .andExpect(jsonPath("$.settlementRoundingMode").value("DOWN"));

        // And the new partner is now readable via GET.
        mvc.perform(get("/v1/admin/partners/{id}", "partner_new_001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partnerId").value("partner_new_001"));
    }

    @Test
    @DisplayName("GET /v1/admin/partners/{id} returns the partner when it exists")
    void getPartner_returns200() throws Exception {
        mvc.perform(get("/v1/admin/partners/{id}", "partner_test_001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partnerId").value("partner_test_001"))
                .andExpect(jsonPath("$.settlementCurrency").value("USD"));
    }

    @Test
    @DisplayName("GET /v1/admin/partners/{id} returns 404 when unknown")
    void getPartner_returns404() throws Exception {
        mvc.perform(get("/v1/admin/partners/{id}", "ghost"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PUT /v1/admin/partners/{id}/rounding-mode updates the partner")
    void updateRoundingMode_returnsUpdated() throws Exception {
        mvc.perform(put("/v1/admin/partners/{id}/rounding-mode", "partner_test_001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"DOWN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partnerId").value("partner_test_001"))
                .andExpect(jsonPath("$.settlementRoundingMode").value("DOWN"));
    }

    @Test
    @DisplayName("PUT /v1/admin/partners/{id}/rounding-mode returns 404 when partner unknown")
    void updateRoundingMode_unknownReturns404() throws Exception {
        mvc.perform(put("/v1/admin/partners/{id}/rounding-mode", "ghost")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"HALF_UP\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /v1/admin/schemes returns the scheme list")
    void schemes_returnsList() throws Exception {
        mvc.perform(get("/v1/admin/schemes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].schemeId").value("zeropay_kr"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));
    }
}
