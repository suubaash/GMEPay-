package com.gme.pay.bff.web;

import static org.hamcrest.Matchers.nullValue;
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
import com.gme.pay.contracts.KybView;
import com.gme.pay.contracts.PartnerRegulatoryConfigView;
import com.gme.pay.contracts.PartnerStatus;
import com.gme.pay.contracts.PartnerView;
import com.gme.pay.contracts.TravelRuleProtocol;
import com.gme.pay.domain.PartnerType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

/**
 * #77 Slice 3 — MockMvc tests for the compliance-overview orchestration ({@link ComplianceOverviewController}),
 * with a Mockito {@link ConfigRegistryClient} so each partial-failure path is exercised precisely.
 */
class ComplianceOverviewControllerTest {

    private MockMvc mvc;
    private ConfigRegistryClient configRegistry;

    @BeforeEach
    void setUp() {
        configRegistry = mock(ConfigRegistryClient.class);
        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mvc = standaloneSetup(new ComplianceOverviewController(configRegistry))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om))
                .build();
    }

    private static PartnerView partner(String code, String romanizedName, PartnerStatus status) {
        return new PartnerView(1L, code, PartnerType.OVERSEAS, "KRW", RoundingMode.HALF_UP,
                "KRW", "KRW", null, romanizedName, null, null, "KR", null, null, null, null, status,
                null, null, null);
    }

    private static KybView kyb(String screeningStatus) {
        return new KybView(1L, null, null, null, null, null, null, null, null, null,
                screeningStatus, null, null, null, null, null);
    }

    private static PartnerRegulatoryConfigView reg(String bokTxnCode, String hometaxCertId,
                                                   String kofiuEntityId, TravelRuleProtocol protocol) {
        return new PartnerRegulatoryConfigView(1L, bokTxnCode, null, null, hometaxCertId, null, kofiuEntityId,
                new BigDecimal("10000000"), List.of(), null, protocol, null, new BigDecimal("1000000"));
    }

    @Test
    @DisplayName("maps a fully-configured partner and isolates a bare partner (404s degrade, never 500)")
    void mapsConfiguredAndIsolatesBare() throws Exception {
        when(configRegistry.listPartnerViews()).thenReturn(List.of(
                partner("GME_KR_001", "GME Korea Co., Ltd.", PartnerStatus.LIVE),
                partner("GME_VN_002", "GME Vietnam Pte.", PartnerStatus.ONBOARDING)));
        when(configRegistry.getKyb("GME_KR_001")).thenReturn(kyb("CLEAR"));
        when(configRegistry.getRegulatory("GME_KR_001"))
                .thenReturn(reg("101", "HT-9981", "KOFIU-GME-001", TravelRuleProtocol.IVMS101));
        // Bare partner: no KYB row + no step-8 regulatory save yet → config-registry 404s.
        when(configRegistry.getKyb("GME_VN_002"))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        when(configRegistry.getRegulatory("GME_VN_002"))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));

        mvc.perform(get("/v1/admin/compliance/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                // configured partner
                .andExpect(jsonPath("$[0].partnerCode").value("GME_KR_001"))
                .andExpect(jsonPath("$[0].partnerName").value("GME Korea Co., Ltd."))
                .andExpect(jsonPath("$[0].kybStatus").value("APPROVED"))         // CLEAR → APPROVED
                .andExpect(jsonPath("$[0].sanctionsResult").value("CLEAR"))      // screeningStatus verbatim
                .andExpect(jsonPath("$[0].lifecycleStatus").value("LIVE"))
                .andExpect(jsonPath("$[0].regulatoryConfig.bokSet").value(true))
                .andExpect(jsonPath("$[0].regulatoryConfig.hometaxSet").value(true))
                .andExpect(jsonPath("$[0].regulatoryConfig.kofiuSet").value(true))
                .andExpect(jsonPath("$[0].regulatoryConfig.travelRuleSet").value(true))
                // bare partner degrades (no 500)
                .andExpect(jsonPath("$[1].partnerCode").value("GME_VN_002"))
                .andExpect(jsonPath("$[1].kybStatus").value("PENDING"))
                .andExpect(jsonPath("$[1].sanctionsResult").value(nullValue()))
                .andExpect(jsonPath("$[1].lifecycleStatus").value("ONBOARDING"))
                .andExpect(jsonPath("$[1].regulatoryConfig.bokSet").value(false))
                .andExpect(jsonPath("$[1].regulatoryConfig.hometaxSet").value(false))
                .andExpect(jsonPath("$[1].regulatoryConfig.kofiuSet").value(false))
                .andExpect(jsonPath("$[1].regulatoryConfig.travelRuleSet").value(false));
    }

    @Test
    @DisplayName("KYB screening verdicts map to the kybStatus enum (NEEDS_REVIEW→REVIEW, HIT→HIT)")
    void kybVerdictMapping() throws Exception {
        when(configRegistry.listPartnerViews()).thenReturn(List.of(
                partner("P_REVIEW", "Review Co", PartnerStatus.LIVE),
                partner("P_HIT", "Hit Co", PartnerStatus.SUSPENDED)));
        when(configRegistry.getKyb("P_REVIEW")).thenReturn(kyb("NEEDS_REVIEW"));
        when(configRegistry.getKyb("P_HIT")).thenReturn(kyb("HIT"));
        when(configRegistry.getRegulatory("P_REVIEW")).thenReturn(reg(null, null, null, TravelRuleProtocol.NONE));
        when(configRegistry.getRegulatory("P_HIT")).thenReturn(reg(null, null, null, TravelRuleProtocol.NONE));

        mvc.perform(get("/v1/admin/compliance/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].kybStatus").value("REVIEW"))
                .andExpect(jsonPath("$[0].sanctionsResult").value("NEEDS_REVIEW"))
                .andExpect(jsonPath("$[1].kybStatus").value("HIT"))
                .andExpect(jsonPath("$[1].sanctionsResult").value("HIT"));
    }

    @Test
    @DisplayName("flag derivation: only set fields flip true; travelRuleProtocol NONE → travelRuleSet false")
    void flagDerivationMatrix() throws Exception {
        when(configRegistry.listPartnerViews())
                .thenReturn(List.of(partner("P1", "Partial Co", PartnerStatus.LIVE)));
        when(configRegistry.getKyb("P1")).thenReturn(kyb("CLEAR"));
        // Only BOK configured; Hometax/KoFIU absent; Travel Rule explicitly NONE.
        when(configRegistry.getRegulatory("P1")).thenReturn(reg("101", null, null, TravelRuleProtocol.NONE));

        mvc.perform(get("/v1/admin/compliance/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].regulatoryConfig.bokSet").value(true))
                .andExpect(jsonPath("$[0].regulatoryConfig.hometaxSet").value(false))
                .andExpect(jsonPath("$[0].regulatoryConfig.kofiuSet").value(false))
                .andExpect(jsonPath("$[0].regulatoryConfig.travelRuleSet").value(false));
    }

    @Test
    @DisplayName("no partners → empty array (200, not 500)")
    void emptyList() throws Exception {
        when(configRegistry.listPartnerViews()).thenReturn(List.of());

        mvc.perform(get("/v1/admin/compliance/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
