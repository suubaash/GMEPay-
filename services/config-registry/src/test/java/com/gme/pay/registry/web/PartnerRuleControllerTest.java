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
import com.gme.pay.registry.persistence.PartnerEntity;
import com.gme.pay.registry.persistence.PartnerRepository;
import com.gme.pay.registry.rule.RuleService;
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
 * Controller slice test for {@link PartnerRuleController} (Slice 6). Runs as a
 * {@code @DataJpaTest} against H2 in PostgreSQL mode with the full Flyway
 * chain (V001..V017); the controller is mounted on a standalone MockMvc — the
 * same pattern as {@code PartnerPrefundingControllerTest}.
 *
 * <p>Pins the wire shape of the decimal fields: margins and money are decimal
 * STRINGS (e.g. {@code "0.0150"}) per {@code docs/MONEY_CONVENTION.md}, never
 * JSON floats.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PartnerRuleControllerTest.TestConfig.class, RuleService.class,
        AuditLogService.class, PartnerStore.class, CacheConfig.class})
class PartnerRuleControllerTest {

    @Autowired
    private RuleService ruleService;

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
        mvc = standaloneSetup(new PartnerRuleController(ruleService))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om))
                .build();
    }

    /** Seed a cross-border draft: collect KRW / settle USD (the Slice 6 exit-gate corridor). */
    private void seedCrossBorderPartner(String code) {
        partnerStore.save(Partner.of(code, PartnerType.OVERSEAS, "USD", RoundingMode.HALF_UP));
        PartnerEntity current = partnerRepository.findCurrentByPartnerCode(code).orElseThrow();
        current.setCollectionCcy("KRW");
        current.setSettleACcy("USD");
        partnerRepository.saveAndFlush(current);
    }

    private static final String STEP6_RULES_BODY = """
            {
              "rules": [
                {
                  "schemeId": "ZEROPAY",
                  "direction": "OUTBOUND",
                  "mA": "0.015",
                  "mB": "0.01",
                  "serviceChargeUsd": "1.50"
                },
                {
                  "schemeId": "ZEROPAY",
                  "direction": "INBOUND",
                  "mA": "0.02",
                  "mB": "0.005"
                }
              ]
            }
            """;

    @Test
    @DisplayName("PATCH /v1/partners/draft/{code}/step-6-rules saves and returns the set (decimals as strings)")
    void patchStep6Rules_savesAndReturnsViews() throws Exception {
        seedCrossBorderPartner("rule_ctrl_001");

        mvc.perform(patch("/v1/partners/draft/{code}/step-6-rules", "rule_ctrl_001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(STEP6_RULES_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").isNumber())
                .andExpect(jsonPath("$[0].schemeId").value("ZEROPAY"))
                .andExpect(jsonPath("$[0].direction").value("OUTBOUND"))
                // MONEY_CONVENTION: decimal STRING on the wire, scale-4 normalized.
                .andExpect(jsonPath("$[0].mA").value("0.0150"))
                .andExpect(jsonPath("$[0].mB").value("0.0100"))
                .andExpect(jsonPath("$[0].serviceChargeUsd").value("1.5000"))
                .andExpect(jsonPath("$[0].recordedAt").isNotEmpty())
                // Null charge defaults to the V017 column DEFAULT 0.
                .andExpect(jsonPath("$[1].direction").value("INBOUND"))
                .andExpect(jsonPath("$[1].serviceChargeUsd").value("0.0000"));
    }

    @Test
    @DisplayName("GET /v1/partners/{code}/rules rehydrates the saved set; replace supersedes it")
    void getRules_returnsCurrentSet() throws Exception {
        seedCrossBorderPartner("rule_ctrl_002");
        mvc.perform(patch("/v1/partners/draft/{code}/step-6-rules", "rule_ctrl_002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(STEP6_RULES_BODY))
                .andExpect(status().isOk());

        mvc.perform(get("/v1/partners/{code}/rules", "rule_ctrl_002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        // Bulk replace: a one-element save supersedes the whole prior set.
        mvc.perform(patch("/v1/partners/draft/{code}/step-6-rules", "rule_ctrl_002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rules":[{"schemeId":"ZEROPAY","direction":"BOTH",
                                           "mA":"0.012","mB":"0.008"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mvc.perform(get("/v1/partners/{code}/rules", "rule_ctrl_002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].direction").value("BOTH"));
    }

    @Test
    @DisplayName("cross-border rule below the 2% combined-margin floor returns 400 with the rules[i] index")
    void marginInvariantBreach_400() throws Exception {
        seedCrossBorderPartner("rule_ctrl_003");

        mvc.perform(patch("/v1/partners/draft/{code}/step-6-rules", "rule_ctrl_003")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rules":[{"schemeId":"ZEROPAY","direction":"OUTBOUND",
                                           "mA":"0.005","mB":"0.005"}]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("bad direction returns 400")
    void badDirection_400() throws Exception {
        seedCrossBorderPartner("rule_ctrl_004");
        mvc.perform(patch("/v1/partners/draft/{code}/step-6-rules", "rule_ctrl_004")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rules":[{"schemeId":"ZEROPAY","direction":"SIDEWAYS",
                                           "mA":"0.015","mB":"0.005"}]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("null rules list returns 400")
    void nullRules_400() throws Exception {
        seedCrossBorderPartner("rule_ctrl_005");
        mvc.perform(patch("/v1/partners/draft/{code}/step-6-rules", "rule_ctrl_005")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("unknown partner returns 404 on both endpoints")
    void unknownPartner_404() throws Exception {
        mvc.perform(patch("/v1/partners/draft/{code}/step-6-rules", "rule_ghost")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(STEP6_RULES_BODY))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/partners/{code}/rules", "rule_ghost"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("partner with zero rules returns an empty list, not 404")
    void partnerWithoutRules_emptyList() throws Exception {
        seedCrossBorderPartner("rule_ctrl_006");
        mvc.perform(get("/v1/partners/{code}/rules", "rule_ctrl_006"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
