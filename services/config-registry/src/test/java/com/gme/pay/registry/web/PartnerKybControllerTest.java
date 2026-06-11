package com.gme.pay.registry.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.gme.pay.registry.kyb.KybService;
import com.gme.pay.registry.kyb.StubKybClient;
import com.gme.pay.registry.partner.PartnerStore;
import com.gme.pay.registry.persistence.PartnerEntity;
import com.gme.pay.registry.persistence.PartnerRepository;
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
 * Controller slice test for {@link PartnerKybController} (Slice 3). Runs as a
 * {@code @DataJpaTest} against H2 in PostgreSQL mode with the full Flyway
 * chain (V001..V011); the controller is mounted on a standalone MockMvc — the
 * same pattern as {@code ChangeRequestControllerTest}. Screening goes through
 * the in-process {@code StubKybClient}, so verdicts are deterministic by
 * legal name.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PartnerKybControllerTest.TestConfig.class, KybService.class, StubKybClient.class,
        AuditLogService.class, PartnerStore.class, CacheConfig.class})
class PartnerKybControllerTest {

    @Autowired
    private KybService kybService;

    @Autowired
    private PartnerStore partnerStore;

    @Autowired
    private PartnerRepository partnerRepository;

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
        mvc = standaloneSetup(new PartnerKybController(kybService))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om))
                .build();
    }

    private void seedPartner(String code, String legalNameRomanized) {
        partnerStore.save(Partner.of(code, PartnerType.OVERSEAS, "USD", RoundingMode.HALF_UP));
        PartnerEntity current = partnerRepository.findCurrentByPartnerCode(code).orElseThrow();
        current.setLegalNameRomanized(legalNameRomanized);
        partnerRepository.saveAndFlush(current);
    }

    private static final String STEP3_BODY = """
            {
              "riskRating": "MEDIUM",
              "riskRationale": "Corridor risk per matrix v2",
              "nextReviewDate": "2027-06-01",
              "licenseType": "REMITTANCE",
              "licenseNumber": "RL-2026-0042",
              "licenseAuthority": "Bank of Korea",
              "licenseExpiry": "2028-12-31",
              "uboList": [
                {"name": "Hong Gil Dong", "ownershipPct": 60, "isPep": false, "country": "KR"},
                {"name": "Kim Pep", "ownershipPct": 40, "isPep": true, "country": "KR"}
              ],
              "cbddqDocId": null
            }
            """;

    @Test
    @DisplayName("PATCH /v1/partners/draft/{code}/step-3 saves and returns the KYB view")
    void patchStep3_savesAndReturnsView() throws Exception {
        seedPartner("kyb_ctrl_001", "Clean Corp Ltd");

        mvc.perform(patch("/v1/partners/draft/{code}/step-3", "kyb_ctrl_001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(STEP3_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.riskRating").value("MEDIUM"))
                .andExpect(jsonPath("$.licenseNumber").value("RL-2026-0042"))
                .andExpect(jsonPath("$.uboList.length()").value(2))
                .andExpect(jsonPath("$.uboList[1].isPep").value(true))
                .andExpect(jsonPath("$.screeningStatus").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.recordedAt").isNotEmpty());
    }

    @Test
    @DisplayName("GET /v1/partners/{code}/kyb rehydrates the saved step-3 state")
    void getKyb_returnsCurrentRow() throws Exception {
        seedPartner("kyb_ctrl_002", "Clean Corp Ltd");
        mvc.perform(patch("/v1/partners/draft/{code}/step-3", "kyb_ctrl_002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(STEP3_BODY))
                .andExpect(status().isOk());

        mvc.perform(get("/v1/partners/{code}/kyb", "kyb_ctrl_002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskRating").value("MEDIUM"))
                .andExpect(jsonPath("$.nextReviewDate").value("2027-06-01"))
                .andExpect(jsonPath("$.uboList[0].name").value("Hong Gil Dong"));
    }

    @Test
    @DisplayName("POST /v1/partners/{code}/kyb/screen stores the stub verdict on the row")
    void screen_storesVerdict() throws Exception {
        seedPartner("kyb_ctrl_003", "Sanctioned Holdings PLC");
        mvc.perform(patch("/v1/partners/draft/{code}/step-3", "kyb_ctrl_003")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(STEP3_BODY))
                .andExpect(status().isOk());

        mvc.perform(post("/v1/partners/{code}/kyb/screen", "kyb_ctrl_003")
                        .header("X-Actor", "checker_lee"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.screeningStatus").value("HIT"))
                .andExpect(jsonPath("$.screeningProviderRef")
                        .value(org.hamcrest.Matchers.startsWith("stub-")))
                .andExpect(jsonPath("$.screenedAt").isNotEmpty())
                // step-3 fields ride along on the screening row
                .andExpect(jsonPath("$.riskRating").value("MEDIUM"));
    }

    @Test
    @DisplayName("unknown partner returns 404 on all three endpoints")
    void unknownPartner_404() throws Exception {
        mvc.perform(patch("/v1/partners/draft/{code}/step-3", "kyb_ghost")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(STEP3_BODY))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/partners/{code}/kyb", "kyb_ghost"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/v1/partners/{code}/kyb/screen", "kyb_ghost"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("bad risk rating returns 400")
    void badRiskRating_400() throws Exception {
        seedPartner("kyb_ctrl_004", "Clean Corp Ltd");
        mvc.perform(patch("/v1/partners/draft/{code}/step-3", "kyb_ctrl_004")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"riskRating\":\"EXTREME\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET kyb before any step-3 save returns 404")
    void getKyb_noRowYet_404() throws Exception {
        seedPartner("kyb_ctrl_005", "Clean Corp Ltd");
        mvc.perform(get("/v1/partners/{code}/kyb", "kyb_ctrl_005"))
                .andExpect(status().isNotFound());
    }
}
