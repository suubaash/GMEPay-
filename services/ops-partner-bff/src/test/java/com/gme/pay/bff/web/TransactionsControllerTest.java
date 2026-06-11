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
 * Standalone MockMvc test for the Phase-C2 transactions search + detail
 * endpoints on {@link AdminDashboardController}. Uses the real stub clients so
 * pagination + filtering exercise both ends.
 */
class TransactionsControllerTest {

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
    @DisplayName("GET /v1/admin/transactions returns paginated content with total")
    void list_returnsPageEnvelope() throws Exception {
        mvc.perform(get("/v1/admin/transactions")
                        .param("page", "0")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.content.length()").value(2));
    }

    @Test
    @DisplayName("GET /v1/admin/transactions filters by partnerId")
    void list_filtersByPartnerId() throws Exception {
        mvc.perform(get("/v1/admin/transactions")
                        .param("partnerId", "partner_test_001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.content[0].partnerId").value("partner_test_001"));
    }

    @Test
    @DisplayName("GET /v1/admin/transactions filters by status")
    void list_filtersByStatus() throws Exception {
        mvc.perform(get("/v1/admin/transactions")
                        .param("status", "FAILED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.content[0].txnId").value("TXN-1004"));
    }

    @Test
    @DisplayName("GET /v1/admin/transactions/{id} returns the transaction detail")
    void detail_returns200() throws Exception {
        mvc.perform(get("/v1/admin/transactions/{id}", "TXN-1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.txnId").value("TXN-1001"))
                .andExpect(jsonPath("$.schemeTxnRef").value("SCH-TXN-1001"))
                .andExpect(jsonPath("$.schemeApprovalCode").value("AP-TXN-1001"))
                .andExpect(jsonPath("$.settlementRoundingMode").value("HALF_UP"))
                .andExpect(jsonPath("$.bookedSettlementAmount").exists());
    }

    @Test
    @DisplayName("GET /v1/admin/transactions/{id} returns 404 when unknown")
    void detail_unknownReturns404() throws Exception {
        mvc.perform(get("/v1/admin/transactions/{id}", "TXN-DOES-NOT-EXIST"))
                .andExpect(status().isNotFound());
    }
}
