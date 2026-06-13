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
import com.gme.pay.registry.regulatory.PartnerRegulatoryConfigService;
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
 * Controller slice test for {@link PartnerRegulatoryConfigController}
 * (Slice 8 Lane C). Runs as a {@code @DataJpaTest} against H2 in PostgreSQL
 * mode with the full Flyway chain (V001..V029.1); the controller is mounted
 * on a standalone MockMvc — the same pattern as
 * {@code PartnerPrefundingControllerTest}.
 *
 * <p>Pins the wire shape: KRW thresholds are decimal STRINGS (e.g.
 * {@code "10000000.00"}) per {@code docs/MONEY_CONVENTION.md}, the PIPA
 * allowlist is a JSON array of ISO codes, the roster fields are bare enum
 * names, and the dual-mount {@code /v1/admin/partners/...} +
 * {@code /v1/partners/...} paths bind to the same service.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PartnerRegulatoryConfigControllerTest.TestConfig.class,
        PartnerRegulatoryConfigService.class,
        AuditLogService.class, PartnerStore.class, CacheConfig.class})
class PartnerRegulatoryConfigControllerTest {

    @Autowired
    private PartnerRegulatoryConfigService regulatoryService;

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
        mvc = standaloneSetup(new PartnerRegulatoryConfigController(regulatoryService))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om))
                .build();
    }

    private void seedPartner(String code) {
        partnerStore.save(Partner.of(code, PartnerType.OVERSEAS, "USD", RoundingMode.HALF_UP));
    }

    private static final String STEP8_BODY = """
            {
              "regulatory": {
                "bokTxnCode": "601",
                "bokFxReportingCategory": "INSTITUTIONAL",
                "bokRemitterType": "CORPORATION",
                "hometaxIssuerCertId": "vault-doc-cert-0001",
                "vatTreatment": "ZERO_RATED_EXPORT",
                "kofiuEntityId": "KOFIU-GME-001",
                "ctrThresholdKrw": "20000000",
                "pipaJurisdictionAllowlist": ["MN", "VN", "KH"],
                "legalBasisCode": "CONTRACT",
                "travelRuleProtocol": "TRP",
                "travelRuleEndpointUrl": "https://trp.partner.example.com/v1/transfers",
                "travelRuleThresholdKrw": "1500000"
              }
            }
            """;

    @Test
    @DisplayName("PATCH /v1/admin/partners/draft/{code}/step-8/regulatory saves and returns"
            + " the view (KRW as strings, allowlist as a JSON array)")
    void patchStep8_savesAndReturnsView() throws Exception {
        seedPartner("reg_ctrl_001");

        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-8/regulatory", "reg_ctrl_001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Actor", "maker_kim")
                        .content(STEP8_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partnerId").isNumber())
                .andExpect(jsonPath("$.bokTxnCode").value("601"))
                .andExpect(jsonPath("$.bokFxReportingCategory").value("INSTITUTIONAL"))
                .andExpect(jsonPath("$.bokRemitterType").value("CORPORATION"))
                .andExpect(jsonPath("$.hometaxIssuerCertId").value("vault-doc-cert-0001"))
                .andExpect(jsonPath("$.vatTreatment").value("ZERO_RATED_EXPORT"))
                .andExpect(jsonPath("$.kofiuEntityId").value("KOFIU-GME-001"))
                // MONEY_CONVENTION: decimal STRING on the wire, scale-2 normalized.
                .andExpect(jsonPath("$.ctrThresholdKrw").value("20000000.00"))
                .andExpect(jsonPath("$.travelRuleThresholdKrw").value("1500000.00"))
                .andExpect(jsonPath("$.pipaJurisdictionAllowlist[0]").value("MN"))
                .andExpect(jsonPath("$.pipaJurisdictionAllowlist[2]").value("KH"))
                .andExpect(jsonPath("$.legalBasisCode").value("CONTRACT"))
                .andExpect(jsonPath("$.travelRuleProtocol").value("TRP"))
                .andExpect(jsonPath("$.travelRuleEndpointUrl")
                        .value("https://trp.partner.example.com/v1/transfers"));
    }

    @Test
    @DisplayName("PATCH with an empty regulatory object applies the V029 statutory defaults")
    void patchStep8_appliesDefaults() throws Exception {
        seedPartner("reg_ctrl_002");

        mvc.perform(patch("/v1/partners/draft/{code}/step-8/regulatory", "reg_ctrl_002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"regulatory\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ctrThresholdKrw").value("10000000.00"))
                .andExpect(jsonPath("$.travelRuleThresholdKrw").value("1000000.00"))
                .andExpect(jsonPath("$.pipaJurisdictionAllowlist").isEmpty())
                .andExpect(jsonPath("$.travelRuleProtocol")
                        .value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @DisplayName("GET /v1/admin/partners/{code}/regulatory rehydrates the saved state"
            + " (both mounts)")
    void getRegulatory_returnsCurrentRow() throws Exception {
        seedPartner("reg_ctrl_003");
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-8/regulatory", "reg_ctrl_003")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(STEP8_BODY))
                .andExpect(status().isOk());

        mvc.perform(get("/v1/admin/partners/{code}/regulatory", "reg_ctrl_003"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kofiuEntityId").value("KOFIU-GME-001"))
                .andExpect(jsonPath("$.ctrThresholdKrw").value("20000000.00"));

        // The registry-internal mount serves the same row.
        mvc.perform(get("/v1/partners/{code}/regulatory", "reg_ctrl_003"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.travelRuleProtocol").value("TRP"));
    }

    @Test
    @DisplayName("off-roster vatTreatment returns 400")
    void badRoster_400() throws Exception {
        seedPartner("reg_ctrl_004");
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-8/regulatory", "reg_ctrl_004")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"regulatory\":{\"vatTreatment\":\"REDUCED_RATE\"}}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Travel-Rule protocol without an endpoint returns 400")
    void protocolWithoutEndpoint_400() throws Exception {
        seedPartner("reg_ctrl_005");
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-8/regulatory", "reg_ctrl_005")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"regulatory\":{\"travelRuleProtocol\":\"SYGNA\"}}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("missing 'regulatory' object returns 400")
    void missingRegulatoryObject_400() throws Exception {
        seedPartner("reg_ctrl_006");
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-8/regulatory", "reg_ctrl_006")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("unknown partner returns 404 on both endpoints")
    void unknownPartner_404() throws Exception {
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-8/regulatory", "reg_ghost")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(STEP8_BODY))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/admin/partners/{code}/regulatory", "reg_ghost"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET regulatory before any step-8 save returns 404")
    void getRegulatory_noRowYet_404() throws Exception {
        seedPartner("reg_ctrl_007");
        mvc.perform(get("/v1/admin/partners/{code}/regulatory", "reg_ctrl_007"))
                .andExpect(status().isNotFound());
    }
}
