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
import com.gme.pay.registry.bank.BankAccountEntity;
import com.gme.pay.registry.bank.BankAccountPurpose;
import com.gme.pay.registry.bank.BankAccountRepository;
import com.gme.pay.registry.cache.CacheConfig;
import com.gme.pay.registry.partner.PartnerStore;
import com.gme.pay.registry.persistence.PartnerRepository;
import com.gme.pay.registry.prefunding.PrefundingConfigService;
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
 * Controller slice test for {@link PartnerPrefundingController} (Slice 5).
 * Runs as a {@code @DataJpaTest} against H2 in PostgreSQL mode with the full
 * Flyway chain (V001..V015); the controller is mounted on a standalone MockMvc
 * — the same pattern as {@code PartnerSettlementControllerTest}.
 *
 * <p>Pins the wire shape of the money fields: decimal STRINGS (e.g.
 * {@code "10000.0000"}) per {@code docs/MONEY_CONVENTION.md}, never JSON
 * floats.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PartnerPrefundingControllerTest.TestConfig.class, PrefundingConfigService.class,
        AuditLogService.class, PartnerStore.class, CacheConfig.class,
        com.gme.pay.registry.prefunding.push.CreditLimitPusher.class,
        com.gme.pay.registry.prefunding.push.NoOpPrefundingCreditLimitClient.class})
class PartnerPrefundingControllerTest {

    @Autowired
    private PrefundingConfigService prefundingService;

    @Autowired
    private PartnerStore partnerStore;

    @Autowired
    private PartnerRepository partnerRepository;

    @Autowired
    private BankAccountRepository bankAccountRepository;

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
        mvc = standaloneSetup(new PartnerPrefundingController(prefundingService))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om))
                .build();
    }

    private void seedPartner(String code) {
        partnerStore.save(Partner.of(code, PartnerType.OVERSEAS, "USD", RoundingMode.HALF_UP));
    }

    private Long seedFloatTopupAccount(String code) {
        Long partnerId = partnerRepository.findCurrentByPartnerCode(code).orElseThrow().getId();
        BankAccountEntity account = new BankAccountEntity();
        account.setPartnerId(partnerId);
        account.setCurrency("USD");
        account.setBankName("Shinhan Bank");
        account.setIbanOrAccountNumber("110-123-456789");
        account.setAccountHolderName("Clean Corp Ltd");
        account.setBankCountry("KR");
        account.setPurpose(BankAccountPurpose.FLOAT_TOPUP);
        return bankAccountRepository.saveAndFlush(account).getId();
    }

    private static final String STEP5_BODY = """
            {
              "fundingModel": "PREFUNDED",
              "openingBalanceUsd": "250000",
              "lowBalanceThresholdUsd": "25000.50",
              "alertTier70": true,
              "alertTier85": true,
              "alertTier95": false,
              "creditLimitUsd": "100000",
              "autoSuspendOnBreach": true,
              "topUpReferencePattern": "GMP-{partner_code}-TOPUP"
            }
            """;

    @Test
    @DisplayName("PATCH /v1/partners/draft/{code}/step-5 saves and returns the view (money as strings)")
    void patchStep5_savesAndReturnsView() throws Exception {
        seedPartner("prefund_ctrl_001");

        mvc.perform(patch("/v1/partners/draft/{code}/step-5", "prefund_ctrl_001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(STEP5_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.fundingModel").value("PREFUNDED"))
                // MONEY_CONVENTION: decimal STRING on the wire, scale-4 normalized.
                .andExpect(jsonPath("$.openingBalanceUsd").value("250000.0000"))
                .andExpect(jsonPath("$.lowBalanceThresholdUsd").value("25000.5000"))
                .andExpect(jsonPath("$.creditLimitUsd").value("100000.0000"))
                .andExpect(jsonPath("$.alertTier95").value(false))
                .andExpect(jsonPath("$.autoSuspendOnBreach").value(true))
                .andExpect(jsonPath("$.topUpReferencePattern").value("GMP-{partner_code}-TOPUP"))
                .andExpect(jsonPath("$.collateralAmountUsd").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.recordedAt").isNotEmpty());
    }

    @Test
    @DisplayName("PATCH with nulls applies the V015 defaults (threshold 10000, tiers on, default pattern)")
    void patchStep5_appliesDefaults() throws Exception {
        seedPartner("prefund_ctrl_002");

        mvc.perform(patch("/v1/partners/draft/{code}/step-5", "prefund_ctrl_002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fundingModel\":\"POSTPAID\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lowBalanceThresholdUsd").value("10000.0000"))
                .andExpect(jsonPath("$.alertTier70").value(true))
                .andExpect(jsonPath("$.alertTier85").value(true))
                .andExpect(jsonPath("$.alertTier95").value(true))
                .andExpect(jsonPath("$.autoSuspendOnBreach").value(true))
                .andExpect(jsonPath("$.topUpReferencePattern")
                        .value("GMP-{partner_code}-{yyyyMMdd}"));
    }

    @Test
    @DisplayName("PATCH with a FLOAT_TOPUP account reference round-trips the id")
    void patchStep5_withTopUpAccount() throws Exception {
        seedPartner("prefund_ctrl_003");
        Long accountId = seedFloatTopupAccount("prefund_ctrl_003");

        mvc.perform(patch("/v1/partners/draft/{code}/step-5", "prefund_ctrl_003")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fundingModel\":\"PREFUNDED\","
                                + "\"floatTopUpBankAccountId\":" + accountId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.floatTopUpBankAccountId").value(accountId));
    }

    @Test
    @DisplayName("GET /v1/partners/{code}/prefunding-config rehydrates the saved state")
    void getPrefundingConfig_returnsCurrentRow() throws Exception {
        seedPartner("prefund_ctrl_004");
        mvc.perform(patch("/v1/partners/draft/{code}/step-5", "prefund_ctrl_004")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(STEP5_BODY))
                .andExpect(status().isOk());

        mvc.perform(get("/v1/partners/{code}/prefunding-config", "prefund_ctrl_004"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fundingModel").value("PREFUNDED"))
                .andExpect(jsonPath("$.lowBalanceThresholdUsd").value("25000.5000"));
    }

    @Test
    @DisplayName("bad funding model returns 400")
    void badModel_400() throws Exception {
        seedPartner("prefund_ctrl_005");
        mvc.perform(patch("/v1/partners/draft/{code}/step-5", "prefund_ctrl_005")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fundingModel\":\"PAY_LATER\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("pattern without {partner_code} returns 400")
    void badPattern_400() throws Exception {
        seedPartner("prefund_ctrl_006");
        mvc.perform(patch("/v1/partners/draft/{code}/step-5", "prefund_ctrl_006")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fundingModel\":\"PREFUNDED\","
                                + "\"topUpReferencePattern\":\"GMP-TOPUP-{yyyyMMdd}\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("unknown partner returns 404 on both endpoints")
    void unknownPartner_404() throws Exception {
        mvc.perform(patch("/v1/partners/draft/{code}/step-5", "prefund_ghost")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(STEP5_BODY))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/partners/{code}/prefunding-config", "prefund_ghost"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET prefunding-config before any step-5 save returns 404")
    void getConfig_noRowYet_404() throws Exception {
        seedPartner("prefund_ctrl_007");
        mvc.perform(get("/v1/partners/{code}/prefunding-config", "prefund_ctrl_007"))
                .andExpect(status().isNotFound());
    }
}
