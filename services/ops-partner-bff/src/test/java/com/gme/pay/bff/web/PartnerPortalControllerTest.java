package com.gme.pay.bff.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gme.pay.bff.client.PrefundingClient;
import com.gme.pay.bff.client.SettlementClient;
import com.gme.pay.bff.client.TransactionMgmtClient;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Standalone MockMvc test for {@link PartnerPortalController}. Builds MockMvc directly around a
 * controller instance with hand-rolled stub clients — no Spring context, no component scanning,
 * so there's no chance of the slice failing to register the @RestController. Same behavior is
 * exercised: balance + recent count + last settlement combined into PartnerOverview, missing
 * partner yields 404.
 */
class PartnerPortalControllerTest {

    private static final String PARTNER = "p1";

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        PrefundingClient prefunding = partnerId -> {
            if (!PARTNER.equals(partnerId)) {
                return null;
            }
            return new PrefundingClient.BalanceView(
                    PARTNER, "USD",
                    new BigDecimal("4321.10"), new BigDecimal("1000.00"));
        };

        TransactionMgmtClient transactions = new TransactionMgmtClient() {
            private final List<TransactionSummary> txns = List.of(
                    new TransactionSummary("T-A", PARTNER, "COMMITTED",
                            new BigDecimal("12.00"), "USD",
                            Instant.parse("2026-06-09T09:00:00Z")),
                    new TransactionSummary("T-B", PARTNER, "COMMITTED",
                            new BigDecimal("34.00"), "USD",
                            Instant.parse("2026-06-09T10:00:00Z")),
                    new TransactionSummary("T-C", PARTNER, "COMMITTED",
                            new BigDecimal("56.00"), "USD",
                            Instant.parse("2026-06-09T11:00:00Z")));

            @Override
            public TransactionSummary getTransaction(String txnId) {
                return null;
            }

            @Override
            public List<TransactionSummary> recent(String partnerId, int limit) {
                return PARTNER.equals(partnerId) ? txns : List.of();
            }
        };

        SettlementClient settlement = (partnerId, limit) -> List.of(
                new SettlementClient.SettlementBatchSummary(
                        "BATCH-20260608-001", PARTNER,
                        LocalDate.of(2026, 6, 8), "USD",
                        new BigDecimal("9876.54"), "COMPLETED"));

        PartnerPortalController controller =
                new PartnerPortalController(transactions, prefunding, settlement);

        // Configure Jackson with JavaTimeModule + ISO strings for Instant/LocalDate (not arrays of numbers).
        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter(om);

        mvc = standaloneSetup(controller).setMessageConverters(converter).build();
    }

    @Test
    @DisplayName("GET /v1/portal/{partnerId}/overview combines balance, recent count and last settlement")
    void overview_combinesAllUpstreams() throws Exception {
        mvc.perform(get("/v1/portal/{p}/overview", PARTNER))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.partnerId").value(PARTNER))
                .andExpect(jsonPath("$.balance.partnerId").value(PARTNER))
                .andExpect(jsonPath("$.balance.currency").value("USD"))
                .andExpect(jsonPath("$.balance.balance").value(4321.10))
                .andExpect(jsonPath("$.recentTxnCount").value(3))
                .andExpect(jsonPath("$.lastSettlementDate").value("2026-06-08"));
    }

    @Test
    @DisplayName("GET /v1/portal/{partnerId}/balance returns the prefunding view")
    void balance_returnsPrefundingView() throws Exception {
        mvc.perform(get("/v1/portal/{p}/balance", PARTNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partnerId").value(PARTNER))
                .andExpect(jsonPath("$.lowBalanceThreshold").value(1000.00));
    }

    @Test
    @DisplayName("GET /v1/portal/{partnerId}/balance returns 404 when partner unknown")
    void balance_unknownPartnerReturns404() throws Exception {
        mvc.perform(get("/v1/portal/{p}/balance", "ghost"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /v1/portal/{partnerId}/transactions returns the recent rows")
    void transactions_returnsRecentRows() throws Exception {
        mvc.perform(get("/v1/portal/{p}/transactions", PARTNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].txnId").value("T-A"));
    }
}
