package com.gme.pay.bff.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gme.pay.bff.client.ConfigRegistryClient;
import com.gme.pay.bff.client.OpsControlClient;
import com.gme.pay.bff.client.PrefundingClient;
import com.gme.pay.bff.client.SettlementClient;
import com.gme.pay.bff.client.SystemHealthClient;
import com.gme.pay.bff.client.TransactionMgmtClient;
import com.gme.pay.bff.client.TransactionMgmtClient.SearchQuery;
import com.gme.pay.bff.client.TransactionMgmtClient.TransactionSummary;
import com.gme.pay.bff.client.WebhookOpsClient;
import com.gme.pay.contracts.BalanceView;
import com.gme.pay.contracts.OperationalStatusView;
import com.gme.pay.contracts.PartnerStatus;
import com.gme.pay.contracts.PartnerView;
import com.gme.pay.domain.PartnerType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * MockMvc tests for {@link ControlTowerController}: composition from mocked upstreams
 * (incl. a DEGRADED section that must NOT 500 the whole tower).
 */
class ControlTowerControllerTest {

    private MockMvc mvc;
    private TransactionMgmtClient transactions;
    private WebhookOpsClient webhooks;
    private PrefundingClient prefunding;
    private SystemHealthClient systemHealth;
    private SettlementClient settlements;
    private ConfigRegistryClient configRegistry;
    private OpsControlClient opsControl;
    private com.gme.pay.bff.alert.OpsAlertStore alerts;

    @BeforeEach
    void setUp() {
        transactions = mock(TransactionMgmtClient.class);
        webhooks = mock(WebhookOpsClient.class);
        prefunding = mock(PrefundingClient.class);
        systemHealth = mock(SystemHealthClient.class);
        settlements = mock(SettlementClient.class);
        configRegistry = mock(ConfigRegistryClient.class);
        opsControl = mock(OpsControlClient.class);

        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        alerts = new com.gme.pay.bff.alert.OpsAlertStore(200);
        mvc = standaloneSetup(new ControlTowerController(
                transactions, webhooks, prefunding, systemHealth, settlements, configRegistry, opsControl, alerts))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om))
                .build();
    }

    private static PartnerView partner(String code) {
        return new PartnerView(1L, code, PartnerType.OVERSEAS, "USD", RoundingMode.HALF_UP,
                "USD", "USD", null, code, null, null, "KR", null, null, null, null, PartnerStatus.LIVE,
                null, null, null);
    }

    private static TransactionMgmtClient.Page<TransactionSummary> pageWithTotal(long total) {
        return new TransactionMgmtClient.Page<>(List.of(), 0, 1, total);
    }

    @Test
    void composesAllSectionsFromMockedUpstreams() throws Exception {
        // in-flight: AUTHORIZED=3, PENDING=2, PROCESSING=0 => 5; UNCERTAIN=4
        when(transactions.search(any(SearchQuery.class))).thenAnswer(inv -> {
            SearchQuery q = inv.getArgument(0);
            long total = switch (q.status()) {
                case "AUTHORIZED" -> 3;
                case "PENDING" -> 2;
                case "UNCERTAIN" -> 4;
                default -> 0;
            };
            return pageWithTotal(total);
        });
        when(webhooks.backlog()).thenReturn(new WebhookOpsClient.WebhookBacklog(7, 2));
        when(configRegistry.listPartnerViews()).thenReturn(List.of(partner("P_A"), partner("P_B")));
        // P_A healthy (200% of threshold), P_B at-risk (below threshold)
        when(prefunding.getAdminBalance("P_A")).thenReturn(BalanceView.of(
                "P_A", "USD", new BigDecimal("2000"), new BigDecimal("1000"), new BigDecimal("200.00")));
        when(prefunding.getAdminBalance("P_B")).thenReturn(BalanceView.of(
                "P_B", "USD", new BigDecimal("500"), new BigDecimal("1000"), new BigDecimal("50.00")));
        when(systemHealth.check()).thenReturn(new SystemHealthClient.SystemHealth(
                Instant.parse("2026-07-01T00:00:00Z"),
                List.of(new SystemHealthClient.ServiceHealth("config-registry", "UP", null, null),
                        new SystemHealthClient.ServiceHealth("prefunding", "DOWN", null, null),
                        new SystemHealthClient.ServiceHealth("rate-fx", "DEGRADED", null, null))));
        when(settlements.openReconExceptions()).thenReturn(3);
        when(opsControl.operationalStatus()).thenReturn(new OperationalStatusView(
                true, false, List.of("P_X"), List.of(), List.of(), "manual pause", "2026-07-01T00:00:00Z"));

        mvc.perform(get("/v1/admin/ops/control-tower"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inFlight.inFlightCount").value(5))
                .andExpect(jsonPath("$.inFlight.uncertainOrAgedCount").value(4))
                .andExpect(jsonPath("$.webhookBacklog.pending").value(7))
                .andExpect(jsonPath("$.webhookBacklog.dlq").value(2))
                .andExpect(jsonPath("$.webhookBacklog.total").value(9))
                .andExpect(jsonPath("$.floatHeadroom.partners.length()").value(2))
                .andExpect(jsonPath("$.floatHeadroom.lowest.partnerId").value("P_B"))
                .andExpect(jsonPath("$.floatHeadroom.lowest.atRisk").value(true))
                .andExpect(jsonPath("$.health.total").value(3))
                .andExpect(jsonPath("$.health.up").value(1))
                .andExpect(jsonPath("$.health.down").value(1))
                .andExpect(jsonPath("$.health.degraded").value(1))
                .andExpect(jsonPath("$.openReconExceptions").value(3))
                .andExpect(jsonPath("$.operationalStatus.systemPaused").value(true))
                .andExpect(jsonPath("$.degradedSections.length()").value(0));
    }

    @Test
    void reflectsConsumedOpsAlerts() throws Exception {
        when(transactions.search(any(SearchQuery.class))).thenReturn(pageWithTotal(0));
        when(webhooks.backlog()).thenReturn(new WebhookOpsClient.WebhookBacklog(0, 0));
        when(configRegistry.listPartnerViews()).thenReturn(List.of());
        when(systemHealth.check()).thenReturn(new SystemHealthClient.SystemHealth(
                Instant.parse("2026-07-01T00:00:00Z"), List.of()));
        when(settlements.openReconExceptions()).thenReturn(0);
        when(opsControl.operationalStatus()).thenReturn(OperationalStatusView.allClear());

        alerts.add(new com.gme.pay.contracts.events.OpsAlertPayload(
                "ops.alert", "FLOAT_LOW", "WARN", "P_B", "float below threshold", "2026-07-01T01:00:00Z"));
        alerts.add(new com.gme.pay.contracts.events.OpsAlertPayload(
                "ops.alert", "STUCK_TXN", "CRITICAL", "TXN-9", "stuck 30m", "2026-07-01T02:00:00Z"));

        mvc.perform(get("/v1/admin/ops/control-tower"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recentAlerts.total").value(2))
                .andExpect(jsonPath("$.recentAlerts.critical").value(1))
                // newest-first: STUCK_TXN was added last
                .andExpect(jsonPath("$.recentAlerts.latest[0].alertType").value("STUCK_TXN"))
                .andExpect(jsonPath("$.recentAlerts.latest[1].alertType").value("FLOAT_LOW"));
    }

    @Test
    void degradedSectionDoesNotFailWholeTower() throws Exception {
        when(transactions.search(any(SearchQuery.class))).thenReturn(pageWithTotal(1));
        // webhook upstream throws -> section degrades, not a 500
        when(webhooks.backlog()).thenThrow(new RuntimeException("notification-webhook unreachable"));
        when(configRegistry.listPartnerViews()).thenReturn(List.of());
        when(systemHealth.check()).thenReturn(new SystemHealthClient.SystemHealth(
                Instant.parse("2026-07-01T00:00:00Z"), List.of()));
        // recon exceptions unknown -> null degrades that section too
        when(settlements.openReconExceptions()).thenReturn(null);
        when(opsControl.operationalStatus()).thenReturn(OperationalStatusView.allClear());

        mvc.perform(get("/v1/admin/ops/control-tower"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.webhookBacklog.pending").value(nullValueJson()))
                .andExpect(jsonPath("$.openReconExceptions").value(nullValueJson()))
                .andExpect(jsonPath("$.degradedSections", org.hamcrest.Matchers.hasItems(
                        "webhookBacklog", "openReconExceptions")))
                .andExpect(jsonPath("$.operationalStatus.systemPaused").value(false));
    }

    private static org.hamcrest.Matcher<Object> nullValueJson() {
        return org.hamcrest.Matchers.nullValue();
    }
}
