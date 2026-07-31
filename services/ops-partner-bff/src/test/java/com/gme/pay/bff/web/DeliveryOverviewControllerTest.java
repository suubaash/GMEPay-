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
import com.gme.pay.contracts.PartnerStatus;
import com.gme.pay.contracts.PartnerView;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Standalone MockMvc test for {@link AdminDashboardController#deliveryOverview}. Hand-rolled stub
 * clients: the transaction-mgmt fake returns a canned {@link TransactionMgmtClient.DeliveryStats}
 * + first-approved map; config-registry returns two {@link PartnerView}s (one onboarded before its
 * first approval, one never approved). Asserts the overview shape incl. activation computed from
 * onboardedAt → firstApprovedAt.
 */
class DeliveryOverviewControllerTest {

    private MockMvc mvc;

    private static final Instant P1_ONBOARDED = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant P1_FIRST_APPROVED = Instant.parse("2026-06-03T12:00:00Z"); // +60h
    private static final Instant P2_ONBOARDED = Instant.parse("2026-06-10T00:00:00Z");

    private static PartnerView partner(String code, Instant onboardedAt) {
        return new PartnerView(
                null, code, com.gme.pay.domain.PartnerType.LOCAL, "KRW", RoundingMode.HALF_UP,
                "KRW", "KRW", null, null, null, null, null, null, null, null, null,
                PartnerStatus.LIVE, onboardedAt, null, onboardedAt, onboardedAt);
    }

    @BeforeEach
    void setUp() {
        ConfigRegistryClient configRegistry = new ConfigRegistryClient() {
            @Override public PartnerSummary getPartner(String id) { return null; }
            @Override public List<PartnerSummary> listPartners() { return List.of(); }
            @Override public PartnerSummary createPartner(PartnerCreateRequest r) { return null; }
            @Override public PartnerSummary updateRoundingMode(String id, String m) { return null; }
            @Override public List<SchemeSummary> listSchemes() { return List.of(); }
            @Override public List<PartnerView> listPartnerViews() {
                return List.of(partner("partner-A", P1_ONBOARDED), partner("partner-B", P2_ONBOARDED));
            }
        };

        TransactionMgmtClient transactions = new TransactionMgmtClient() {
            @Override public TransactionSummary getTransaction(String txnId) { return null; }
            @Override public List<TransactionSummary> recent(String partnerId, int limit) { return List.of(); }
            @Override public Page<TransactionSummary> list(Filter filter) {
                return new Page<>(List.of(), 0, 20, 0);
            }
            @Override public DeliveryStats stats(Instant from, Instant to) {
                return new DeliveryStats(
                        new DeliveryStats.Window(
                                Instant.parse("2026-06-01T00:00:00Z"),
                                Instant.parse("2026-07-01T00:00:00Z")),
                        new DeliveryStats.Totals(8, 4, 3, 50.0),
                        List.of(new DeliveryStats.PartnerStat("partner-A", 5, 3, 2, 60.0),
                                new DeliveryStats.PartnerStat("partner-B", 3, 1, 1, 33.3)),
                        List.of(new DeliveryStats.CorridorStat("zeropay", 5, 3, 2, 60.0)),
                        List.of(new DeliveryStats.DeclineReason("APPROVAL_TIMEOUT", 2),
                                new DeliveryStats.DeclineReason("CANCELLED", 1)));
            }
            @Override public Map<String, Instant> firstApprovedByPartner() {
                return Map.of("partner-A", P1_FIRST_APPROVED);   // partner-B intentionally absent
            }
        };

        PrefundingClient prefunding = partnerId -> null;
        RevenueLedgerClient revenue = new RevenueLedgerClient() {
            @Override public RevenueSummary getSummary(java.time.LocalDate date) { return null; }
            @Override public RevenueSummary summaryRange(java.time.LocalDate from, java.time.LocalDate to) { return null; }
            @Override public RevenueBreakdown breakdown(java.time.LocalDate from, java.time.LocalDate to) {
                return new RevenueBreakdown(Map.of(), Map.of(), Map.of());
            }
        };
        SettlementClient settlement = new SettlementClient() {
            @Override public List<SettlementBatchSummary> recent(String p, int l) { return List.of(); }
            @Override public SettlementBatchDetail detail(String b) { return null; }
        };

        AdminDashboardController controller = new AdminDashboardController(
                configRegistry, transactions, prefunding, revenue, settlement, new OpsRbacGuard(false));

        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mvc = standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om))
                .build();
    }

    @Test
    @DisplayName("GET /v1/admin/delivery/overview returns success rate, decline reasons and activation")
    void deliveryOverview_shape() throws Exception {
        mvc.perform(get("/v1/admin/delivery/overview"))
                .andExpect(status().isOk())
                // window echoes the upstream-resolved window
                .andExpect(jsonPath("$.window.from").value("2026-06-01T00:00:00Z"))
                // success rate: overall + slices
                .andExpect(jsonPath("$.successRate.overall.total").value(8))
                .andExpect(jsonPath("$.successRate.overall.approved").value(4))
                .andExpect(jsonPath("$.successRate.overall.successRatePct").value(50.0))
                .andExpect(jsonPath("$.successRate.byPartner.length()").value(2))
                .andExpect(jsonPath("$.successRate.byCorridor[0].corridor").value("zeropay"))
                // decline reasons pass through
                .andExpect(jsonPath("$.declineReasons.length()").value(2))
                .andExpect(jsonPath("$.declineReasons[0].reason").value("APPROVAL_TIMEOUT"))
                .andExpect(jsonPath("$.declineReasons[0].count").value(2))
                // activation: partner-A activated (60h between onboarded and first-approved)
                .andExpect(jsonPath("$.activation.length()").value(2))
                .andExpect(jsonPath("$.activation[0].partner").value("partner-A"))
                .andExpect(jsonPath("$.activation[0].status").value("activated"))
                .andExpect(jsonPath("$.activation[0].activationHours").value(60))
                .andExpect(jsonPath("$.activation[0].firstApprovedAt").value("2026-06-03T12:00:00Z"))
                // activation: partner-B pending (never approved)
                .andExpect(jsonPath("$.activation[1].partner").value("partner-B"))
                .andExpect(jsonPath("$.activation[1].status").value("pending"))
                .andExpect(jsonPath("$.activation[1].firstApprovedAt").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.activation[1].activationHours").value(org.hamcrest.Matchers.nullValue()));
    }
}
