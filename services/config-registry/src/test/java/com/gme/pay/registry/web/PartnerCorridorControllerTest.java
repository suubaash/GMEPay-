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
import com.gme.pay.registry.corridor.PartnerCorridorService;
import com.gme.pay.registry.partner.PartnerStore;
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
 * Controller slice test for {@link PartnerCorridorController} (Slice 7). Runs
 * as a {@code @DataJpaTest} against H2 in PostgreSQL mode with the full Flyway
 * chain; the controller is mounted on a standalone MockMvc — the same pattern
 * as {@code PartnerRuleControllerTest}.
 *
 * <p>Pins the dual mount: the Slice 7 admin surface
 * ({@code /v1/admin/partners/...}) and the registry-internal convention
 * ({@code /v1/partners/...}) both bind to the same service methods.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PartnerCorridorControllerTest.TestConfig.class, PartnerCorridorService.class,
        AuditLogService.class, PartnerStore.class, CacheConfig.class})
class PartnerCorridorControllerTest {

    @Autowired
    private PartnerCorridorService corridorService;

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
        mvc = standaloneSetup(new PartnerCorridorController(corridorService))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om))
                .build();
    }

    private void seedPartner(String code) {
        partnerStore.save(Partner.of(code, PartnerType.OVERSEAS, "USD", RoundingMode.HALF_UP));
    }

    private static final String STEP7_CORRIDORS_BODY = """
            {
              "corridors": [
                {
                  "srcCountry": "KR",
                  "srcCcy": "KRW",
                  "dstCountry": "MN",
                  "dstCcy": "MNT",
                  "goLiveDate": "2026-07-01",
                  "isActive": true
                },
                {
                  "srcCountry": "KR",
                  "srcCcy": "KRW",
                  "dstCountry": "VN",
                  "dstCcy": "VND"
                }
              ]
            }
            """;

    @Test
    @DisplayName("PATCH /v1/admin/partners/draft/{code}/step-7/corridors saves and returns the set")
    void patchStep7Corridors_savesAndReturnsViews() throws Exception {
        seedPartner("corr_ctrl_001");

        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-7/corridors", "corr_ctrl_001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(STEP7_CORRIDORS_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].partnerId").isNumber())
                .andExpect(jsonPath("$[0].srcCountry").value("KR"))
                .andExpect(jsonPath("$[0].srcCcy").value("KRW"))
                .andExpect(jsonPath("$[0].dstCountry").value("MN"))
                .andExpect(jsonPath("$[0].dstCcy").value("MNT"))
                .andExpect(jsonPath("$[0].goLiveDate").value("2026-07-01"))
                .andExpect(jsonPath("$[0].isActive").value(true))
                // Omitted goLiveDate stays null (ALWAYS-include contract);
                // omitted isActive defaults to the V023 column DEFAULT TRUE.
                .andExpect(jsonPath("$[1].goLiveDate").value((Object) null))
                .andExpect(jsonPath("$[1].isActive").value(true));
    }

    @Test
    @DisplayName("GET /v1/admin/partners/{code}/corridors rehydrates the saved set; replace supersedes it")
    void getCorridors_returnsCurrentSet() throws Exception {
        seedPartner("corr_ctrl_002");
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-7/corridors", "corr_ctrl_002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(STEP7_CORRIDORS_BODY))
                .andExpect(status().isOk());

        mvc.perform(get("/v1/admin/partners/{code}/corridors", "corr_ctrl_002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        // Bulk replace: a one-element save supersedes the whole prior set.
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-7/corridors", "corr_ctrl_002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"corridors":[{"srcCountry":"KR","srcCcy":"KRW",
                                               "dstCountry":"KH","dstCcy":"KHR",
                                               "isActive":false}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mvc.perform(get("/v1/admin/partners/{code}/corridors", "corr_ctrl_002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].dstCcy").value("KHR"))
                .andExpect(jsonPath("$[0].isActive").value(false));
    }

    @Test
    @DisplayName("the registry-convention /v1/partners/... aliases bind to the same handlers")
    void conventionAliases_serveTheSameSurface() throws Exception {
        seedPartner("corr_ctrl_003");

        mvc.perform(patch("/v1/partners/draft/{code}/step-7/corridors", "corr_ctrl_003")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(STEP7_CORRIDORS_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        mvc.perform(get("/v1/partners/{code}/corridors", "corr_ctrl_003"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].dstCcy").value("MNT"));
    }

    @Test
    @DisplayName("bad ISO codes and duplicate lanes return 400")
    void badPayload_400() throws Exception {
        seedPartner("corr_ctrl_004");
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-7/corridors", "corr_ctrl_004")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"corridors":[{"srcCountry":"KOREA","srcCcy":"KRW",
                                               "dstCountry":"MN","dstCcy":"MNT"}]}
                                """))
                .andExpect(status().isBadRequest());

        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-7/corridors", "corr_ctrl_004")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"corridors":[
                                  {"srcCountry":"KR","srcCcy":"KRW","dstCountry":"MN","dstCcy":"MNT"},
                                  {"srcCountry":"KR","srcCcy":"KRW","dstCountry":"MN","dstCcy":"MNT"}]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("null corridors list returns 400")
    void nullCorridors_400() throws Exception {
        seedPartner("corr_ctrl_005");
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-7/corridors", "corr_ctrl_005")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("unknown partner returns 404 on both endpoints; zero corridors is an empty list")
    void unknownPartner404_emptySetIsEmptyList() throws Exception {
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-7/corridors", "corr_ghost")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(STEP7_CORRIDORS_BODY))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/admin/partners/{code}/corridors", "corr_ghost"))
                .andExpect(status().isNotFound());

        seedPartner("corr_ctrl_006");
        mvc.perform(get("/v1/admin/partners/{code}/corridors", "corr_ctrl_006"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
