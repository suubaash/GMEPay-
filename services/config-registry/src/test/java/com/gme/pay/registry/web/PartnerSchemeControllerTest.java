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
import com.gme.pay.registry.scheme.PartnerSchemeService;
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
 * Controller slice test for {@link PartnerSchemeController} (Slice 7). Runs as
 * a {@code @DataJpaTest} against H2 in PostgreSQL mode with the full Flyway
 * chain (V001..V024); the controller is mounted on a standalone MockMvc — the
 * same pattern as {@code PartnerRuleControllerTest}.
 *
 * <p>Pins the {@code /v1/admin} mount of the step-7 surface and the wire shape
 * of the operating-hours rows ({@code LocalTime}s as ISO strings).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PartnerSchemeControllerTest.TestConfig.class, PartnerSchemeService.class,
        AuditLogService.class, PartnerStore.class, CacheConfig.class})
class PartnerSchemeControllerTest {

    @Autowired
    private PartnerSchemeService schemeService;

    @Autowired
    private PartnerStore partnerStore;

    @Autowired
    private com.gme.pay.registry.persistence.PartnerRepository partnerRepository;

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
        mvc = standaloneSetup(new PartnerSchemeController(schemeService),
                        new SchemeResolutionController(schemeService))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om))
                .build();
    }

    private void seedPartner(String code) {
        partnerStore.save(Partner.of(code, PartnerType.OVERSEAS, "USD", RoundingMode.HALF_UP));
    }

    private static final String STEP7_SCHEMES_BODY = """
            {
              "schemes": [
                {
                  "schemeId": "ZEROPAY",
                  "direction": "OUTBOUND",
                  "role": "ACQUIRER",
                  "zeropayMerchantId": "ZPM-0001",
                  "zeropaySubMerchantId": "ZPSM-0001",
                  "kftcInstitutionCode": "KFTC097",
                  "partnerTypeChar": "D",
                  "vaultSecretId": "vault-zp-1",
                  "approvalMethodCpm": "CONFIRMATION",
                  "approvalMethodMpm": "SILENT",
                  "enabled": true
                },
                {
                  "schemeId": "BAKONG",
                  "direction": "INBOUND",
                  "role": "ISSUER"
                }
              ]
            }
            """;

    @Test
    @DisplayName("PATCH /v1/admin/partners/draft/{code}/step-7/schemes saves and returns the set")
    void patchStep7Schemes_savesAndReturnsViews() throws Exception {
        seedPartner("sch_ctrl_001");

        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-7/schemes", "sch_ctrl_001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(STEP7_SCHEMES_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].partnerId").isNumber())
                .andExpect(jsonPath("$[0].schemeId").value("ZEROPAY"))
                .andExpect(jsonPath("$[0].direction").value("OUTBOUND"))
                .andExpect(jsonPath("$[0].role").value("ACQUIRER"))
                .andExpect(jsonPath("$[0].zeropayMerchantId").value("ZPM-0001"))
                .andExpect(jsonPath("$[0].kftcInstitutionCode").value("KFTC097"))
                .andExpect(jsonPath("$[0].partnerTypeChar").value("D"))
                .andExpect(jsonPath("$[0].approvalMethodCpm").value("CONFIRMATION"))
                .andExpect(jsonPath("$[0].approvalMethodMpm").value("SILENT"))
                .andExpect(jsonPath("$[0].enabled").value(true))
                // enabled omitted defaults to true; nullable wiring stays null on the wire.
                .andExpect(jsonPath("$[1].schemeId").value("BAKONG"))
                .andExpect(jsonPath("$[1].enabled").value(true))
                .andExpect(jsonPath("$[1].zeropayMerchantId").value((Object) null));
    }

    @Test
    @DisplayName("GET /v1/admin/partners/{code}/schemes rehydrates the saved set; replace supersedes it")
    void getSchemes_returnsCurrentSet() throws Exception {
        seedPartner("sch_ctrl_002");
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-7/schemes", "sch_ctrl_002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(STEP7_SCHEMES_BODY))
                .andExpect(status().isOk());

        mvc.perform(get("/v1/admin/partners/{code}/schemes", "sch_ctrl_002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        // Bulk replace: a one-element save supersedes the whole prior set.
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-7/schemes", "sch_ctrl_002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"schemes":[{"schemeId":"FAST_SG","direction":"BOTH",
                                             "role":"BOTH"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mvc.perform(get("/v1/admin/partners/{code}/schemes", "sch_ctrl_002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].schemeId").value("FAST_SG"));
    }

    @Test
    @DisplayName("enabled ZEROPAY without merchantId+institutionCode returns 400 (VALIDATION_ERROR)")
    void zeropayMissingWiring_400() throws Exception {
        seedPartner("sch_ctrl_003");

        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-7/schemes", "sch_ctrl_003")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"schemes":[{"schemeId":"ZEROPAY","direction":"OUTBOUND",
                                             "role":"ACQUIRER","enabled":true}]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("null schemes list returns 400")
    void nullSchemes_400() throws Exception {
        seedPartner("sch_ctrl_004");
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-7/schemes", "sch_ctrl_004")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("unknown partner returns 404 on both partner endpoints")
    void unknownPartner_404() throws Exception {
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-7/schemes", "sch_ghost")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(STEP7_SCHEMES_BODY))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/admin/partners/{code}/schemes", "sch_ghost"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /v1/admin/schemes/{schemeId}/operating-hours returns the 7-row schedule")
    void operatingHours_zeropaySchedule() throws Exception {
        mvc.perform(get("/v1/admin/schemes/{schemeId}/operating-hours", "ZEROPAY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(7))
                .andExpect(jsonPath("$[0].schemeId").value("ZEROPAY"))
                .andExpect(jsonPath("$[0].weekday").value(0))
                .andExpect(jsonPath("$[6].weekday").value(6))
                // jsr310 LocalTime strings on the wire (HH:mm:ss).
                .andExpect(jsonPath("$[0].openTimeLocal").value("00:00:00"))
                .andExpect(jsonPath("$[0].closeTimeLocal").value("23:59:59"))
                .andExpect(jsonPath("$[0].cutoffTimeLocal").value("16:30:00"))
                .andExpect(jsonPath("$[0].timezone").value("Asia/Seoul"));

        // No-cutoff rail: cutoffTimeLocal stays on the wire as null (ALWAYS include).
        mvc.perform(get("/v1/admin/schemes/{schemeId}/operating-hours", "FAST_SG"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(7))
                .andExpect(jsonPath("$[0].cutoffTimeLocal").value((Object) null))
                .andExpect(jsonPath("$[0].timezone").value("Asia/Singapore"));
    }

    @Test
    @DisplayName("operating-hours: unknown scheme 404s, rostered-but-unseeded scheme returns []")
    void operatingHours_unknownVsUnseeded() throws Exception {
        mvc.perform(get("/v1/admin/schemes/{schemeId}/operating-hours", "ALIPAY"))
                .andExpect(status().isNotFound());

        mvc.perform(get("/v1/admin/schemes/{schemeId}/operating-hours", "QRIS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("GET /v1/schemes/resolve?country= carries country + derived fields, filters")
    void resolveByLocation_returnsLocationViews() throws Exception {
        seedPartner("sch_loc_ctrl");
        // Stamp operating country so the cross-partner read joins it on.
        var p = partnerRepository.findCurrentByPartnerCode("sch_loc_ctrl").orElseThrow();
        p.setOperatingCountry("KR");
        partnerRepository.saveAndFlush(p);

        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-7/schemes", "sch_loc_ctrl")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(STEP7_SCHEMES_BODY))
                .andExpect(status().isOk());

        mvc.perform(get("/v1/schemes/resolve").param("country", "KR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].countryCode").value("KR"))
                .andExpect(jsonPath("$[0].schemeId").value("ZEROPAY"))
                .andExpect(jsonPath("$[0].supportsCpm").value(true))
                .andExpect(jsonPath("$[0].supportsMpm").value(true))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                // BAKONG declares no approval method → supports neither mode.
                .andExpect(jsonPath("$[1].schemeId").value("BAKONG"))
                .andExpect(jsonPath("$[1].supportsCpm").value(false));

        // Unknown country → empty list (no 404).
        mvc.perform(get("/v1/schemes/resolve").param("country", "ZZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
