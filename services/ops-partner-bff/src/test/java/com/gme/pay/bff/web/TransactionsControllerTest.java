package com.gme.pay.bff.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

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
                configRegistry, transactions, prefunding, revenue, settlement, new OpsRbacGuard(false));

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
                // REAL scheme-confirmation values pass through from transaction-mgmt (no "SCH-"/"AP-" stub)
                .andExpect(jsonPath("$.schemeTxnRef").value("ZP-TXN-1001-CONF"))
                .andExpect(jsonPath("$.schemeApprovalCode").value("AUTH-1001"))
                .andExpect(jsonPath("$.merchantId").value("M0000000001"))
                .andExpect(jsonPath("$.settlementRoundingMode").value("HALF_UP"))
                // settlement booking is locked at settlement time, not payment time → absent here
                .andExpect(jsonPath("$.bookedSettlementAmount").doesNotExist());
    }

    @Test
    @DisplayName("GET /v1/admin/transactions/{id} returns 404 when unknown")
    void detail_unknownReturns404() throws Exception {
        mvc.perform(get("/v1/admin/transactions/{id}", "TXN-DOES-NOT-EXIST"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("CS: detail surfaces failureReason/statusLabel/declineReasonText/statusHistory")
    void detail_carriesCsFields() throws Exception {
        TransactionMgmtClient txns = mock(TransactionMgmtClient.class);
        TransactionMgmtClient.TransactionSummary summary = new TransactionMgmtClient.TransactionSummary(
                "TXN-FAIL", "partner_test_001", "FAILED",
                new BigDecimal("10.00"), "USD", Instant.parse("2026-06-09T10:15:30Z"),
                null, null, null, null, null, null, null, null, null, null, null,
                "SCHEME_DECLINED", "Declined", "Insufficient funds at issuer",
                List.of(
                        TransactionMgmtClient.StatusEntry.of("CREATED", Instant.parse("2026-06-09T10:15:30Z")),
                        new TransactionMgmtClient.StatusEntry("FAILED", "Declined",
                                Instant.parse("2026-06-09T10:15:32Z"), "issuer NSF")));
        when(txns.getTransaction("TXN-FAIL")).thenReturn(summary);

        AdminDashboardController controller = new AdminDashboardController(
                new StubConfigRegistryClient(), txns, new StubPrefundingClient(),
                new StubRevenueLedgerClient(), new StubSettlementClient(), new OpsRbacGuard(false));
        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        MockMvc local = standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om)).build();

        local.perform(get("/v1/admin/transactions/{id}", "TXN-FAIL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failureReason").value("SCHEME_DECLINED"))
                .andExpect(jsonPath("$.statusLabel").value("Declined"))
                .andExpect(jsonPath("$.declineReasonText").value("Insufficient funds at issuer"))
                .andExpect(jsonPath("$.statusHistory.length()").value(2))
                .andExpect(jsonPath("$.statusHistory[1].status").value("FAILED"))
                .andExpect(jsonPath("$.statusHistory[1].statusLabel").value("Declined"))
                .andExpect(jsonPath("$.statusHistory[1].note").value("issuer NSF"));
    }

    @Test
    @DisplayName("CS: txn.view can read detail; missing permission is 403 (fail-closed)")
    void detail_requiresTxnView() throws Exception {
        TransactionMgmtClient txns = mock(TransactionMgmtClient.class);
        when(txns.getTransaction(any())).thenReturn(
                TransactionMgmtClient.TransactionSummary.of("TXN-1001", "partner_test_001",
                        "COMMITTED", new BigDecimal("1.00"), "USD", Instant.parse("2026-06-09T10:15:30Z")));
        // enforce=true → fail-closed on txn.view.
        AdminDashboardController controller = new AdminDashboardController(
                new StubConfigRegistryClient(), txns, new StubPrefundingClient(),
                new StubRevenueLedgerClient(), new StubSettlementClient(), new OpsRbacGuard(true));
        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        MockMvc local = standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om)).build();

        // txn.view (support agent, no ops:operate) CAN read the detail.
        local.perform(get("/v1/admin/transactions/{id}", "TXN-1001")
                        .header("X-Gme-Permissions", "txn.view"))
                .andExpect(status().isOk());
        // No permission presented → denied.
        local.perform(get("/v1/admin/transactions/{id}", "TXN-1001"))
                .andExpect(status().isForbidden());
    }
}
