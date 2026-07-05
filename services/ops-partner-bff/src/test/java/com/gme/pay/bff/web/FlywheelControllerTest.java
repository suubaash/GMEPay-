package com.gme.pay.bff.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gme.pay.bff.client.ConfigRegistryClient;
import com.gme.pay.bff.client.PlatformSettingsClient;
import com.gme.pay.bff.client.PrefundingClient;
import com.gme.pay.bff.client.RevenueLedgerClient;
import com.gme.pay.bff.client.TransactionMgmtClient;
import com.gme.pay.bff.web.dto.PlatformSettingView;
import com.gme.pay.contracts.PartnerStatus;
import com.gme.pay.contracts.PartnerView;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Standalone MockMvc test for {@link FlywheelController}. Hand-rolled stub clients
 * (same style as {@link DeliveryOverviewControllerTest}): 2 partner views (one LIVE +
 * activated, one ONBOARDING), 2 schemes (one ACTIVE), one page of 2 APPROVED
 * transactions summing 150.00 USD TPV, 300 USD prefund, 3.00 USD revenue, and the
 * three {@code flywheel.*} ops-entered settings. Asserts each of the 7 loop metrics
 * plus the null-not-zero behaviour for unset settings.
 */
class FlywheelControllerTest {

    private static final Instant P1_ONBOARDED = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant P1_FIRST_APPROVED = Instant.parse("2026-06-03T12:00:00Z"); // +60h
    private static final Instant P2_ONBOARDED = Instant.parse("2026-06-10T00:00:00Z");

    private MockMvc mvc;
    private List<PlatformSettingView> settings;
    private TransactionMgmtClient.PayerStats payerStatsResponse;

    private static PartnerView partner(String code, PartnerStatus status, Instant onboardedAt) {
        return new PartnerView(
                null, code, com.gme.pay.domain.PartnerType.LOCAL, "KRW", RoundingMode.HALF_UP,
                "KRW", "KRW", null, null, null, null, null, null, null, null, null,
                status, onboardedAt, null, onboardedAt);
    }

    private static TransactionMgmtClient.TransactionSummary approvedTxn(String id, String deductedUsd) {
        return new TransactionMgmtClient.TransactionSummary(
                id, "partner-A", "APPROVED", new BigDecimal("100000"), "KRW",
                Instant.parse("2026-06-15T00:00:00Z"),
                null, null, null, null, null, null,
                new BigDecimal(deductedUsd),
                null, null, null, Instant.parse("2026-06-15T00:00:05Z"),
                null, null, null, null);
    }

    private static PlatformSettingView setting(String key, String value) {
        return new PlatformSettingView(key, value, "NUMBER", null, null, "ops");
    }

    @BeforeEach
    void setUp() {
        settings = List.of(
                setting(FlywheelController.SETTING_ACCEPTANCE_POINTS, "15000"),
                setting(FlywheelController.SETTING_ADAPTER_TTL_DAYS, "45"),
                setting(FlywheelController.SETTING_ACTIVE_PAYERS, "4"));

        ConfigRegistryClient configRegistry = new ConfigRegistryClient() {
            @Override public PartnerSummary getPartner(String id) { return null; }
            @Override public List<PartnerSummary> listPartners() {
                return List.of(new PartnerSummary(
                        "partner-A", "LOCAL", "KRW", RoundingMode.HALF_UP));
            }
            @Override public PartnerSummary createPartner(PartnerCreateRequest r) { return null; }
            @Override public PartnerSummary updateRoundingMode(String id, String m) { return null; }
            @Override public List<SchemeSummary> listSchemes() {
                return List.of(
                        new SchemeSummary("zeropay", "ZeroPay", "KR", "KRW", "MPM", "ACTIVE"),
                        new SchemeSummary("nepalpay", "NepalPay", "NP", "NPR", "MPM", "PLANNED"));
            }
            @Override public List<PartnerView> listPartnerViews() {
                return List.of(
                        partner("partner-A", PartnerStatus.LIVE, P1_ONBOARDED),
                        partner("partner-B", PartnerStatus.ONBOARDING, P2_ONBOARDED));
            }
        };

        TransactionMgmtClient transactions = new TransactionMgmtClient() {
            @Override public TransactionSummary getTransaction(String txnId) { return null; }
            @Override public List<TransactionSummary> recent(String partnerId, int limit) { return List.of(); }
            @Override public Page<TransactionSummary> list(Filter filter) {
                if (filter.page() > 0) {
                    return new Page<>(List.of(), filter.page(), filter.size(), 2);
                }
                return new Page<>(
                        List.of(approvedTxn("TXN-1", "100.50"), approvedTxn("TXN-2", "49.50")),
                        0, filter.size(), 2);
            }
            @Override public Map<String, Instant> firstApprovedByPartner() {
                return Map.of("partner-A", P1_FIRST_APPROVED);   // partner-B intentionally absent
            }
            @Override public PayerStats payerStats(Instant from, Instant to) {
                return payerStatsResponse;
            }
        };

        PrefundingClient prefunding = partnerId ->
                new PrefundingClient.BalanceView(partnerId, "USD",
                        new BigDecimal("300.00"), new BigDecimal("50.00"));

        RevenueLedgerClient revenue = new RevenueLedgerClient() {
            @Override public RevenueSummary getSummary(LocalDate date) { return null; }
            @Override public RevenueSummary summaryRange(LocalDate from, LocalDate to) {
                return new RevenueSummary(from,
                        new BigDecimal("3.00"), new BigDecimal("2.00"), new BigDecimal("1.00"));
            }
            @Override public RevenueBreakdown breakdown(LocalDate from, LocalDate to) {
                return new RevenueBreakdown(Map.of(), Map.of(), Map.of());
            }
        };

        PlatformSettingsClient platformSettings = new PlatformSettingsClient() {
            @Override public List<PlatformSettingView> list() { return settings; }
            @Override public PlatformSettingView get(String key) { return null; }
            @Override public PlatformSettingView update(String key, String value, String updatedBy) { return null; }
        };

        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mvc = standaloneSetup(new FlywheelController(
                configRegistry, transactions, prefunding, revenue, platformSettings))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
    }

    @Test
    @DisplayName("GET /v1/admin/flywheel composes all 7 loop metrics")
    void flywheelComposesAllMetrics() throws Exception {
        mvc.perform(get("/v1/admin/flywheel")
                        .param("from", "2026-06-01T00:00:00Z")
                        .param("to", "2026-07-01T00:00:00Z"))
                .andExpect(status().isOk())
                // metric 1+2 — network sides
                .andExpect(jsonPath("$.network.liveWallets").value(1))
                .andExpect(jsonPath("$.network.totalWallets").value(2))
                .andExpect(jsonPath("$.network.liveSchemes").value(1))
                .andExpect(jsonPath("$.network.totalSchemes").value(2))
                .andExpect(jsonPath("$.network.acceptancePoints").value(15000))
                // metric 3 — TPV + take rate: 100.50 + 49.50 = 150.00; 3.00/150.00 = 2.00%
                .andExpect(jsonPath("$.volume.tpvUsd").value(150.00))
                .andExpect(jsonPath("$.volume.tpvTruncated").value(false))
                .andExpect(jsonPath("$.volume.approvedTxnCount").value(2))
                .andExpect(jsonPath("$.volume.revenueUsd").value(3.00))
                .andExpect(jsonPath("$.volume.takeRatePct").value(2.00))
                // metric 4 — prefunding turn: 150.00 / 300.00 = 0.50
                .andExpect(jsonPath("$.capital.totalPrefundUsd").value(300.00))
                .andExpect(jsonPath("$.capital.prefundingTurnRatio").value(0.50))
                // metric 5 — activation: partner-A onboarded → first approved = 60h median
                .andExpect(jsonPath("$.loopHealth.medianPartnerTimeToFirstTxnHours").value(60))
                .andExpect(jsonPath("$.loopHealth.activatedPartnerCount").value(1))
                .andExpect(jsonPath("$.loopHealth.pendingPartnerCount").value(1))
                // metric 6 — adapter time-to-live (ops-entered)
                .andExpect(jsonPath("$.loopHealth.adapterTimeToLiveDays").value(45))
                // metric 7 — payers: 2 approved txns / 4 payers = 0.5
                .andExpect(jsonPath("$.loopHealth.monthlyActivePayers").value(4))
                .andExpect(jsonPath("$.loopHealth.txnsPerPayer").value(0.5))
                // window echoes the request
                .andExpect(jsonPath("$.window.from").value("2026-06-01T00:00:00Z"))
                .andExpect(jsonPath("$.window.to").value("2026-07-01T00:00:00Z"));
    }

    @Test
    @DisplayName("system-of-record payer stats (user_ref) override the ops-entered setting")
    void computedPayerStatsWinOverSetting() throws Exception {
        payerStatsResponse = new TransactionMgmtClient.PayerStats(
                new TransactionMgmtClient.PayerStats.Window(
                        Instant.parse("2026-06-01T00:00:00Z"), Instant.parse("2026-07-01T00:00:00Z")),
                8);
        mvc.perform(get("/v1/admin/flywheel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loopHealth.monthlyActivePayers").value(8))
                // 2 approved txns / 8 computed payers = 0.3 (scale 1, HALF_UP)
                .andExpect(jsonPath("$.loopHealth.txnsPerPayer").value(0.3));
    }

    @Test
    @DisplayName("unset flywheel.* settings render as null, never fake zeros")
    void unsetSettingsAreNull() throws Exception {
        // V040 seeds the keys at '0' = "not yet measured"; a missing row means the same.
        settings = List.of(setting(FlywheelController.SETTING_ACCEPTANCE_POINTS, "0"));
        mvc.perform(get("/v1/admin/flywheel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.network.acceptancePoints").isEmpty())
                .andExpect(jsonPath("$.loopHealth.adapterTimeToLiveDays").isEmpty())
                .andExpect(jsonPath("$.loopHealth.monthlyActivePayers").isEmpty())
                .andExpect(jsonPath("$.loopHealth.txnsPerPayer").isEmpty());
    }
}
