package com.gme.pay.registry.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gme.pay.audit.RecordingAuditPublisher;
import com.gme.pay.domain.Partner;
import com.gme.pay.domain.PartnerType;
import com.gme.pay.registry.audit.AuditLogService;
import com.gme.pay.registry.cache.CacheConfig;
import com.gme.pay.registry.partner.PartnerStore;
import com.gme.pay.registry.settlement.SettlementConfigService;
import java.math.RoundingMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Controller slice test for {@link PartnerSettlementController} (Slice 4).
 * Runs as a {@code @DataJpaTest} against H2 in PostgreSQL mode with the full
 * Flyway chain (V001..V014 — including the REAL business-day seed); the
 * controller is mounted on a standalone MockMvc — the same pattern as
 * {@code PartnerKybControllerTest}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PartnerSettlementControllerTest.TestConfig.class, SettlementConfigService.class,
        AuditLogService.class, PartnerStore.class, CacheConfig.class})
class PartnerSettlementControllerTest {

    @Autowired
    private SettlementConfigService settlementService;

    @Autowired
    private PartnerStore partnerStore;

    private MockMvc mvc;

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        RecordingAuditPublisher recordingAuditPublisher() {
            return new RecordingAuditPublisher();
        }

        @Bean
        com.gme.pay.audit.AuditPublisher auditPublisher(RecordingAuditPublisher recording) {
            return recording;
        }
    }

    @BeforeEach
    void setUp() {
        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mvc = standaloneSetup(new PartnerSettlementController(settlementService))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om))
                .build();
    }

    private void seedPartner(String code) {
        partnerStore.save(Partner.of(code, PartnerType.OVERSEAS, "USD", RoundingMode.HALF_UP));
    }

    private static final String STEP4_BODY = """
            {
              "cycleTPlusN": 1,
              "cutoffTime": "16:30",
              "cutoffTimezone": "Asia/Seoul",
              "settlementMethod": "KR_FIRM_BANKING"
            }
            """;

    @Test
    @DisplayName("PATCH /v1/partners/draft/{code}/step-4-settlement saves and returns the view")
    void patchStep4Settlement_savesAndReturnsView() throws Exception {
        seedPartner("settle_ctrl_001");

        mvc.perform(patch("/v1/partners/draft/{code}/step-4-settlement", "settle_ctrl_001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(STEP4_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.cycleTPlusN").value(1))
                .andExpect(jsonPath("$.cutoffTime").value("16:30:00"))
                .andExpect(jsonPath("$.cutoffTimezone").value("Asia/Seoul"))
                .andExpect(jsonPath("$.settlementMethod").value("KR_FIRM_BANKING"))
                .andExpect(jsonPath("$.recordedAt").isNotEmpty());
    }

    @Test
    @DisplayName("PATCH with nulls applies the V013 defaults (T+1, 16:30, Asia/Seoul)")
    void patchStep4Settlement_appliesDefaults() throws Exception {
        seedPartner("settle_ctrl_002");

        mvc.perform(patch("/v1/partners/draft/{code}/step-4-settlement", "settle_ctrl_002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settlementMethod\":\"SWIFT_MT103\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cycleTPlusN").value(1))
                .andExpect(jsonPath("$.cutoffTime").value("16:30:00"))
                .andExpect(jsonPath("$.cutoffTimezone").value("Asia/Seoul"));
    }

    @Test
    @DisplayName("GET /v1/partners/{code}/settlement-config rehydrates the saved state")
    void getSettlementConfig_returnsCurrentRow() throws Exception {
        seedPartner("settle_ctrl_003");
        mvc.perform(patch("/v1/partners/draft/{code}/step-4-settlement", "settle_ctrl_003")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(STEP4_BODY))
                .andExpect(status().isOk());

        mvc.perform(get("/v1/partners/{code}/settlement-config", "settle_ctrl_003"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settlementMethod").value("KR_FIRM_BANKING"))
                .andExpect(jsonPath("$.cutoffTime").value("16:30:00"));
    }

    @Test
    @DisplayName("GET settlement-preview projects through the V014 seed (Chuseok roll)")
    void getSettlementPreview_chuseokRoll() throws Exception {
        seedPartner("settle_ctrl_004");
        mvc.perform(patch("/v1/partners/draft/{code}/step-4-settlement", "settle_ctrl_004")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(STEP4_BODY))
                .andExpect(status().isOk());

        // Wed 2026-09-23 17:30 KST (after cutoff) + T+1 over the Chuseok block
        // -> Tue 2026-09-29 (the plan's exit-gate example).
        mvc.perform(get("/v1/partners/{code}/settlement-preview", "settle_ctrl_004")
                        .param("txnInstant", "2026-09-23T08:30:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payoutDate").value("2026-09-29"))
                .andExpect(jsonPath("$.explanation").isArray())
                .andExpect(jsonPath("$.explanation",
                        org.hamcrest.Matchers.hasItem(
                                org.hamcrest.Matchers.containsString("Chuseok"))));
    }

    @Test
    @DisplayName("GET settlement-preview honours the bankCountry union (KR + KH)")
    void getSettlementPreview_bankCountryUnion() throws Exception {
        seedPartner("settle_ctrl_005");
        mvc.perform(patch("/v1/partners/draft/{code}/step-4-settlement", "settle_ctrl_005")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(STEP4_BODY))
                .andExpect(status().isOk());

        mvc.perform(get("/v1/partners/{code}/settlement-preview", "settle_ctrl_005")
                        .param("txnInstant", "2026-10-08T01:00:00Z")
                        .param("bankCountry", "KH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payoutDate").value("2026-10-13"))
                .andExpect(jsonPath("$.explanation",
                        org.hamcrest.Matchers.hasItem(
                                org.hamcrest.Matchers.containsString("Pchum Ben"))));
    }

    @Test
    @DisplayName("malformed txnInstant returns 400 with a readable message")
    void badTxnInstant_400() throws Exception {
        seedPartner("settle_ctrl_006");
        mvc.perform(patch("/v1/partners/draft/{code}/step-4-settlement", "settle_ctrl_006")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(STEP4_BODY))
                .andExpect(status().isOk());

        mvc.perform(get("/v1/partners/{code}/settlement-preview", "settle_ctrl_006")
                        .param("txnInstant", "next tuesday"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("bad settlement method returns 400")
    void badMethod_400() throws Exception {
        seedPartner("settle_ctrl_007");
        mvc.perform(patch("/v1/partners/draft/{code}/step-4-settlement", "settle_ctrl_007")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settlementMethod\":\"CARRIER_PIGEON\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("unknown partner returns 404 on all three endpoints")
    void unknownPartner_404() throws Exception {
        mvc.perform(patch("/v1/partners/draft/{code}/step-4-settlement", "settle_ghost")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(STEP4_BODY))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/partners/{code}/settlement-config", "settle_ghost"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/partners/{code}/settlement-preview", "settle_ghost")
                        .param("txnInstant", "2026-06-10T01:00:00Z"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET settlement-config before any step-4 save returns 404")
    void getConfig_noRowYet_404() throws Exception {
        seedPartner("settle_ctrl_008");
        mvc.perform(get("/v1/partners/{code}/settlement-config", "settle_ctrl_008"))
                .andExpect(status().isNotFound());
    }
}
