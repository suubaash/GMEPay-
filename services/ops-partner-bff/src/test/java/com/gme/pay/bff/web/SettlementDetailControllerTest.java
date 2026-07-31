package com.gme.pay.bff.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Standalone MockMvc test for the Phase-C2 settlement detail endpoint on
 * {@link AdminDashboardController}. Verifies the batch + lines envelope and
 * the 404 path.
 */
class SettlementDetailControllerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ConfigRegistryClient configRegistry = new StubConfigRegistryClient();
        TransactionMgmtClient transactions = new StubTransactionMgmtClient();
        PrefundingClient prefunding = new StubPrefundingClient();
        RevenueLedgerClient revenue = new StubRevenueLedgerClient();
        SettlementClient settlement = new StubSettlementClient();

        AdminDashboardController controller = new AdminDashboardController(
                configRegistry, transactions, prefunding, revenue, settlement, new OpsRbacGuard(false));

        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter(om);

        mvc = standaloneSetup(controller).setMessageConverters(converter).build();
    }

    @Test
    @DisplayName("GET /v1/admin/settlement/{batchId} returns batch + lines + matched/open counts")
    void detail_returns200() throws Exception {
        mvc.perform(get("/v1/admin/settlement/{batchId}", "ZP0061-20260608-MORNING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batch.batchId").value("ZP0061-20260608-MORNING"))
                .andExpect(jsonPath("$.batch.partnerId").value("partner_test_001"))
                .andExpect(jsonPath("$.lines.length()").value(3))
                .andExpect(jsonPath("$.lines[0].txnRef").value("TXN-1001"))
                .andExpect(jsonPath("$.lines[2].matched").value(false))
                .andExpect(jsonPath("$.matchedCount").value(2))
                .andExpect(jsonPath("$.openCount").value(1));
    }

    /**
     * GAP T4-5: the drawer must not be able to show a settlement as done/sent. The seeded batch is
     * {@code RECONCILED} — the confirmation file tied out — and still reports that nothing left the
     * platform, because no transmission channel exists in any environment.
     */
    @Test
    @DisplayName("T4-5: the batch reports a real status and is never presented as transmitted")
    void detail_isHonestAboutTransmission() throws Exception {
        mvc.perform(get("/v1/admin/settlement/{batchId}", "ZP0061-20260608-MORNING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batch.status").value("RECONCILED"))
                .andExpect(jsonPath("$.batch.transmissionState")
                        .value("NOT_TRANSMITTED_CHANNEL_UNAVAILABLE"))
                .andExpect(jsonPath("$.batch.transmittedAt").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.batch.transmissionReason")
                        .value(org.hamcrest.Matchers.containsString("never sent")));
    }

    @Test
    @DisplayName("T4-5: no stub batch anywhere says COMPLETED, and none is transmitted")
    void recentSettlements_carryNoInventedStatus() throws Exception {
        mvc.perform(get("/v1/admin/settlement/recent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].status",
                        org.hamcrest.Matchers.everyItem(
                                org.hamcrest.Matchers.not(org.hamcrest.Matchers.is("COMPLETED")))))
                .andExpect(jsonPath("$[*].transmissionState",
                        org.hamcrest.Matchers.everyItem(
                                org.hamcrest.Matchers.is("NOT_TRANSMITTED_CHANNEL_UNAVAILABLE"))));
    }

    @Test
    @DisplayName("T4-5: the transmission-channel board is exposed and reports no live channel")
    void transmissionChannelBoard() throws Exception {
        mvc.perform(get("/v1/admin/settlement/transmission-channel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.live").value(false))
                .andExpect(jsonPath("$.reachableState").value("NOT_TRANSMITTED_CHANNEL_UNAVAILABLE"))
                .andExpect(jsonPath("$.reason")
                        .value(org.hamcrest.Matchers.containsString("never sent")));
    }

    @Test
    @DisplayName("GET /v1/admin/settlement/{batchId} returns 404 when unknown")
    void detail_unknownReturns404() throws Exception {
        mvc.perform(get("/v1/admin/settlement/{batchId}", "BATCH-DOES-NOT-EXIST"))
                .andExpect(status().isNotFound());
    }
}
