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
 * Standalone MockMvc test for the Phase-C2 revenue endpoints on
 * {@link AdminDashboardController}. Verifies the summary scales across a date
 * range and the breakdown has all three axes.
 */
class RevenueControllerTest {

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
    @DisplayName("GET /v1/admin/revenue/summary returns the range total")
    void summary_returnsRangeTotal() throws Exception {
        // 3 days inclusive (06-08, 06-09, 06-10) -> daily 1234.56 * 3 = 3703.68
        mvc.perform(get("/v1/admin/revenue/summary")
                        .param("from", "2026-06-08")
                        .param("to",   "2026-06-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-06-10"))
                .andExpect(jsonPath("$.totalRevenueUsd").value(3703.68));
    }

    @Test
    @DisplayName("GET /v1/admin/revenue/breakdown returns all three axes")
    void breakdown_returnsAllAxes() throws Exception {
        mvc.perform(get("/v1/admin/revenue/breakdown")
                        .param("from", "2026-06-01")
                        .param("to",   "2026-06-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.byPartner.partner_test_001").value(540.20))
                .andExpect(jsonPath("$.byScheme.zeropay_kr").value(680.00))
                .andExpect(jsonPath("$.byCurrency.USD").value(810.10));
    }
}
