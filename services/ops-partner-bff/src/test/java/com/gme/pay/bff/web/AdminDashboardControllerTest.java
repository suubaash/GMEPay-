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
import com.gme.pay.bff.client.RevenueLedgerClient;
import com.gme.pay.bff.client.SettlementClient;
import com.gme.pay.bff.client.TransactionMgmtClient;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Standalone MockMvc test for {@link AdminDashboardController}. Builds MockMvc directly around a
 * controller instance with hand-rolled stub clients — no Spring context, no component scanning.
 *
 * <p>Verifies the controller correctly:
 * <ul>
 *   <li>counts recent transactions returned by the transaction-mgmt stub;
 *   <li>counts partners returned by the config-registry stub;
 *   <li>counts partners whose prefunding balance is at or below the threshold;
 *   <li>surfaces today's revenue from the revenue-ledger stub.
 * </ul>
 */
class AdminDashboardControllerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ConfigRegistryClient configRegistry = new ConfigRegistryClient() {
            private final List<PartnerSummary> partners = List.of(
                    new PartnerSummary("p1", "OVERSEAS", "USD", RoundingMode.HALF_UP),
                    new PartnerSummary("p2", "DOMESTIC", "KRW", RoundingMode.DOWN),
                    new PartnerSummary("p3", "OVERSEAS", "JPY", RoundingMode.HALF_EVEN));

            @Override
            public PartnerSummary getPartner(String id) {
                return partners.stream()
                        .filter(p -> Objects.equals(p.partnerId(), id))
                        .findFirst().orElse(null);
            }

            @Override
            public List<PartnerSummary> listPartners() {
                return partners;
            }
        };

        TransactionMgmtClient transactions = new TransactionMgmtClient() {
            private final List<TransactionSummary> txns = List.of(
                    new TransactionSummary("T-1", "p1", "COMMITTED",
                            new BigDecimal("10.00"), "USD",
                            Instant.parse("2026-06-09T10:00:00Z")),
                    new TransactionSummary("T-2", "p2", "COMMITTED",
                            new BigDecimal("5000"), "KRW",
                            Instant.parse("2026-06-09T11:00:00Z")));

            @Override
            public TransactionSummary getTransaction(String txnId) {
                return null;
            }

            @Override
            public List<TransactionSummary> recent(String partnerId, int limit) {
                return txns;
            }
        };

        // p2 and p3 are at-or-below threshold; the controller should count 2 low-balance partners.
        PrefundingClient prefunding = partnerId -> switch (partnerId) {
            case "p1" -> new PrefundingClient.BalanceView(
                    "p1", "USD", new BigDecimal("10000.00"), new BigDecimal("1000.00"));
            case "p2" -> new PrefundingClient.BalanceView(
                    "p2", "KRW", new BigDecimal("500000"), new BigDecimal("1000000"));
            case "p3" -> new PrefundingClient.BalanceView(
                    "p3", "JPY", new BigDecimal("50000"), new BigDecimal("100000"));
            default -> null;
        };

        RevenueLedgerClient revenue = date -> new RevenueLedgerClient.RevenueSummary(
                date == null ? LocalDate.now() : date,
                new BigDecimal("999.99"),
                new BigDecimal("300.00"),
                new BigDecimal("699.99"));

        SettlementClient settlement = (partnerId, limit) -> List.of();

        AdminDashboardController controller =
                new AdminDashboardController(configRegistry, transactions, prefunding, revenue, settlement);

        // Jackson with JavaTimeModule + ISO strings for Instant/LocalDate (not arrays of numbers).
        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter(om);

        mvc = standaloneSetup(controller).setMessageConverters(converter).build();
    }

    @Test
    @DisplayName("GET /v1/admin/dashboard returns aggregated counts and today's revenue")
    void dashboard_returnsAggregatedCounts() throws Exception {
        mvc.perform(get("/v1/admin/dashboard"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.recentTxnCount").value(2))
                .andExpect(jsonPath("$.partnerCount").value(3))
                .andExpect(jsonPath("$.lowBalanceCount").value(2))
                .andExpect(jsonPath("$.todayRevenueUsd").value(999.99));
    }

    @Test
    @DisplayName("GET /v1/admin/partners returns the partner list from config-registry")
    void partners_returnsListFromConfigRegistry() throws Exception {
        mvc.perform(get("/v1/admin/partners"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].partnerId").value("p1"))
                .andExpect(jsonPath("$[0].settlementRoundingMode").value("HALF_UP"));
    }

    @Test
    @DisplayName("GET /v1/admin/transactions/recent returns rows from transaction-mgmt")
    void recentTransactions_returnsRowsFromTxnMgmt() throws Exception {
        mvc.perform(get("/v1/admin/transactions/recent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].txnId").value("T-1"))
                .andExpect(jsonPath("$[1].txnId").value("T-2"));
    }
}
