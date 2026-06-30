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
import com.gme.pay.registry.commercial.CommercialTermsService;
import com.gme.pay.registry.commercial.ContractService;
import com.gme.pay.registry.commercial.FeeScheduleService;
import com.gme.pay.registry.commercial.PartnerCommissionShareService;
import com.gme.pay.registry.commercial.FxConfigService;
import com.gme.pay.registry.commercial.LimitsService;
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
 * Controller slice test for {@link PartnerCommercialTermsController}
 * (Slice 6). Runs as a {@code @DataJpaTest} against H2 in PostgreSQL mode
 * with the full Flyway chain (V001..V021); the controller is mounted on a
 * standalone MockMvc — the same pattern as
 * {@code PartnerPrefundingControllerTest}.
 *
 * <p>Pins the wire shape: money and bps as decimal STRINGS (e.g.
 * {@code "1.5000"}, {@code "85.0000"}) per {@code docs/MONEY_CONVENTION.md},
 * contract term dates as ISO strings, untouched composite sections as JSON
 * null — and the 소액해외송금업 server-side cap rejection over HTTP.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PartnerCommercialTermsControllerTest.TestConfig.class, CommercialTermsService.class,
        FeeScheduleService.class, FxConfigService.class, LimitsService.class,
        ContractService.class, PartnerCommissionShareService.class, AuditLogService.class,
        PartnerStore.class, CacheConfig.class,
        com.gme.pay.registry.prefunding.push.CreditLimitPusher.class,
        com.gme.pay.registry.prefunding.push.NoOpPrefundingCreditLimitClient.class})
class PartnerCommercialTermsControllerTest {

    @Autowired
    private CommercialTermsService commercialTermsService;

    @Autowired
    private FeeScheduleService feeScheduleService;

    @Autowired
    private FxConfigService fxConfigService;

    @Autowired
    private LimitsService limitsService;

    @Autowired
    private ContractService contractService;

    @Autowired
    private PartnerCommissionShareService commissionShareService;

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
        mvc = standaloneSetup(new PartnerCommercialTermsController(commercialTermsService,
                feeScheduleService, fxConfigService, limitsService, contractService,
                commissionShareService))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om))
                .build();
    }

    private void seedPartner(String code) {
        partnerStore.save(Partner.of(code, PartnerType.OVERSEAS, "USD", RoundingMode.HALF_UP));
    }

    private static final String FULL_BODY = """
            {
              "feeSchedules": [
                {
                  "schemeId": "zeropay_kr",
                  "direction": "OUTBOUND",
                  "fixedFeeUsd": "1.50",
                  "bpsFee": "25",
                  "tiers": [
                    {"fromVolumeUsd": "10000", "bpsOverride": "20"},
                    {"fromVolumeUsd": "50000", "bpsOverride": "15"}
                  ]
                }
              ],
              "fxConfig": {
                "marginBps": "85",
                "referenceRateSource": "SEOUL_FX_BROKER",
                "quoteHoldSeconds": 600
              },
              "limits": {
                "perTxnMinUsd": "1",
                "perTxnMaxUsd": "5000",
                "annualCapUsd": "50000",
                "licenseType": "SOAEK_HAEOEMONG"
              },
              "contract": {
                "effectiveFrom": "2026-07-01",
                "effectiveTo": "2028-06-30",
                "autoRenewal": true,
                "noticePeriodDays": 90,
                "refundChargebackPolicy": "SHARED"
              }
            }
            """;

    @Test
    @DisplayName("PATCH /v1/partners/draft/{code}/step-6-commercial saves all sections (money as strings)")
    void patchStep6_fullComposite_savesAndReturnsView() throws Exception {
        seedPartner("comm_ctrl_001");

        mvc.perform(patch("/v1/partners/draft/{code}/step-6-commercial", "comm_ctrl_001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(FULL_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feeSchedules[0].id").isNumber())
                .andExpect(jsonPath("$.feeSchedules[0].schemeId").value("zeropay_kr"))
                .andExpect(jsonPath("$.feeSchedules[0].direction").value("OUTBOUND"))
                // MONEY_CONVENTION: decimal STRING on the wire, scale-4 normalized.
                .andExpect(jsonPath("$.feeSchedules[0].fixedFeeUsd").value("1.5000"))
                .andExpect(jsonPath("$.feeSchedules[0].bpsFee").value("25.0000"))
                .andExpect(jsonPath("$.feeSchedules[0].tiers[0].fromVolumeUsd")
                        .value("10000.0000"))
                .andExpect(jsonPath("$.feeSchedules[0].tiers[1].bpsOverride").value("15.0000"))
                .andExpect(jsonPath("$.fxConfig.marginBps").value("85.0000"))
                .andExpect(jsonPath("$.fxConfig.referenceRateSource").value("SEOUL_FX_BROKER"))
                .andExpect(jsonPath("$.fxConfig.quoteHoldSeconds").value(600))
                .andExpect(jsonPath("$.limits.perTxnMaxUsd").value("5000.0000"))
                .andExpect(jsonPath("$.limits.licenseType").value("SOAEK_HAEOEMONG"))
                .andExpect(jsonPath("$.contract.effectiveFrom").value("2026-07-01"))
                .andExpect(jsonPath("$.contract.effectiveTo").value("2028-06-30"))
                .andExpect(jsonPath("$.contract.autoRenewal").value(true))
                .andExpect(jsonPath("$.contract.refundChargebackPolicy").value("SHARED"));
    }

    @Test
    @DisplayName("PATCH with only one section leaves the others null in the response")
    void patchStep6_partialComposite_untouchedSectionsNull() throws Exception {
        seedPartner("comm_ctrl_002");

        mvc.perform(patch("/v1/partners/draft/{code}/step-6-commercial", "comm_ctrl_002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fxConfig\":{\"referenceRateSource\":\"MID_MARKET\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feeSchedules").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.fxConfig.referenceRateSource").value("MID_MARKET"))
                // V019 defaults applied to omitted fields.
                .andExpect(jsonPath("$.fxConfig.marginBps").value("0.0000"))
                .andExpect(jsonPath("$.fxConfig.quoteHoldSeconds").value(300))
                .andExpect(jsonPath("$.limits").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.contract").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @DisplayName("GETs per sub-resource rehydrate the saved state")
    void getSubResources_rehydrate() throws Exception {
        seedPartner("comm_ctrl_003");
        mvc.perform(patch("/v1/partners/draft/{code}/step-6-commercial", "comm_ctrl_003")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(FULL_BODY))
                .andExpect(status().isOk());

        mvc.perform(get("/v1/partners/{code}/fee-schedules", "comm_ctrl_003"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].schemeId").value("zeropay_kr"))
                .andExpect(jsonPath("$[0].tiers[0].fromVolumeUsd").value("10000.0000"));
        mvc.perform(get("/v1/partners/{code}/fx-config", "comm_ctrl_003"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marginBps").value("85.0000"));
        mvc.perform(get("/v1/partners/{code}/limits", "comm_ctrl_003"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.annualCapUsd").value("50000.0000"));
        mvc.perform(get("/v1/partners/{code}/contract", "comm_ctrl_003"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effectiveFrom").value("2026-07-01"));
    }

    @Test
    @DisplayName("소액해외송금업 cap breach (perTxnMax 5001) rejects with 400 over HTTP")
    void soaekCapBreach_400() throws Exception {
        seedPartner("comm_ctrl_004");

        mvc.perform(patch("/v1/partners/draft/{code}/step-6-commercial", "comm_ctrl_004")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"limits": {"perTxnMaxUsd": "5001",
                                            "annualCapUsd": "50000",
                                            "licenseType": "SOAEK_HAEOEMONG"}}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("empty composite body returns 400")
    void emptyComposite_400() throws Exception {
        seedPartner("comm_ctrl_005");
        mvc.perform(patch("/v1/partners/draft/{code}/step-6-commercial", "comm_ctrl_005")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("unknown partner returns 404 on the PATCH and all GETs")
    void unknownPartner_404() throws Exception {
        mvc.perform(patch("/v1/partners/draft/{code}/step-6-commercial", "comm_ghost")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(FULL_BODY))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/partners/{code}/fee-schedules", "comm_ghost"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/partners/{code}/fx-config", "comm_ghost"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/partners/{code}/limits", "comm_ghost"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/partners/{code}/contract", "comm_ghost"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GETs before any step-6 save: empty fee list, 404 single-row sub-resources")
    void getBeforeSave_emptyListAnd404s() throws Exception {
        seedPartner("comm_ctrl_006");
        mvc.perform(get("/v1/partners/{code}/fee-schedules", "comm_ctrl_006"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
        mvc.perform(get("/v1/partners/{code}/fx-config", "comm_ctrl_006"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/partners/{code}/limits", "comm_ctrl_006"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/partners/{code}/contract", "comm_ctrl_006"))
                .andExpect(status().isNotFound());
    }
}
