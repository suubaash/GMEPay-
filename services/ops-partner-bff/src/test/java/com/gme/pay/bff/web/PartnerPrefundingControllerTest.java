package com.gme.pay.bff.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gme.pay.bff.client.stub.StubConfigRegistryClient;
import com.gme.pay.contracts.BankAccountCommand;
import com.gme.pay.contracts.PartnerCommand;
import com.gme.pay.domain.PartnerType;
import java.math.RoundingMode;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Slice 5 (5A.1) MockMvc test for the BFF's prefunding-config pass-throughs on
 * {@link PartnerPrefundingController} —
 * {@code PATCH .../draft/{code}/step-5} and
 * {@code GET .../{code}/prefunding-config}. Uses the real
 * {@link StubConfigRegistryClient} (the same wiring the BFF runs when
 * {@code gmepay.config-registry.client} is not {@code rest}) so the round-trip
 * create-draft → save bank accounts → save prefunding → rehydrate is exercised
 * end-to-end, mirroring {@link PartnerSettlementControllerTest}.
 *
 * <p>The FLOAT_TOPUP purpose rule is pinned both ways here: the stub validates
 * the referenced account against its own step-4 bank-account set, the same
 * check config-registry's {@code PrefundingConfigService} runs against V012.
 */
class PartnerPrefundingControllerTest {

    private MockMvc mvc;
    private StubConfigRegistryClient configRegistry;

    @BeforeEach
    void setUp() {
        configRegistry = new StubConfigRegistryClient();

        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mvc = standaloneSetup(new PartnerPrefundingController(configRegistry))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om))
                .build();
    }

    /** Seed a draft straight through the stub (the draft endpoints live on another controller). */
    private void createDraft(String partnerCode) {
        configRegistry.createDraft(new PartnerCommand.CreateDraft(
                partnerCode, PartnerType.OVERSEAS, "USD", RoundingMode.HALF_UP,
                null, "Clean Corp Ltd", null, null, "KR", null, null, null, null));
    }

    /** Seed one step-4 bank account through the stub; returns the minted row id. */
    private Long seedBankAccount(String partnerCode, String purpose) {
        return configRegistry.patchDraftStep4(partnerCode, new PartnerCommand.UpdateStep4(
                        List.of(new BankAccountCommand(
                                "USD", "Shinhan Bank", null, "110-123-456789",
                                "Clean Corp Ltd", "KR", null, null, null, null, purpose))))
                .get(0).id();
    }

    private static final String STEP5_BODY = """
            {
              "fundingModel": "PREFUNDED",
              "openingBalanceUsd": "250000",
              "lowBalanceThresholdUsd": "25000.50",
              "alertTier95": false,
              "topUpReferencePattern": "GMP-{partner_code}-TOPUP"
            }
            """;

    @Test
    @DisplayName("PATCH /v1/admin/partners/draft/{code}/step-5 saves and returns the view (money as strings)")
    void patchStep5_savesAndReturnsView() throws Exception {
        createDraft("prefund_partner_001");

        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-5", "prefund_partner_001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(STEP5_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.fundingModel").value("PREFUNDED"))
                // MONEY_CONVENTION: decimal STRING on the wire, scale-4 normalized.
                .andExpect(jsonPath("$.openingBalanceUsd").value("250000.0000"))
                .andExpect(jsonPath("$.lowBalanceThresholdUsd").value("25000.5000"))
                .andExpect(jsonPath("$.alertTier70").value(true))
                .andExpect(jsonPath("$.alertTier95").value(false))
                .andExpect(jsonPath("$.autoSuspendOnBreach").value(true))
                .andExpect(jsonPath("$.topUpReferencePattern").value("GMP-{partner_code}-TOPUP"));
    }

    @Test
    @DisplayName("PATCH with nulls applies the V015 defaults (mirrored by the stub)")
    void patchStep5_appliesDefaults() throws Exception {
        createDraft("prefund_partner_002");

        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-5", "prefund_partner_002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fundingModel\":\"POSTPAID\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lowBalanceThresholdUsd").value("10000.0000"))
                .andExpect(jsonPath("$.alertTier70").value(true))
                .andExpect(jsonPath("$.alertTier85").value(true))
                .andExpect(jsonPath("$.alertTier95").value(true))
                .andExpect(jsonPath("$.topUpReferencePattern")
                        .value("GMP-{partner_code}-{yyyyMMdd}"));
    }

    @Test
    @DisplayName("PATCH referencing a FLOAT_TOPUP step-4 account round-trips the id")
    void patchStep5_floatTopupAccount_accepted() throws Exception {
        createDraft("prefund_partner_003");
        Long accountId = seedBankAccount("prefund_partner_003", "FLOAT_TOPUP");

        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-5", "prefund_partner_003")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fundingModel\":\"PREFUNDED\","
                                + "\"floatTopUpBankAccountId\":" + accountId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.floatTopUpBankAccountId").value(accountId));
    }

    @Test
    @DisplayName("PATCH referencing a PAYOUT account returns 400 (purpose must be FLOAT_TOPUP)")
    void patchStep5_wrongPurposeAccount_400() throws Exception {
        createDraft("prefund_partner_004");
        Long payoutAccountId = seedBankAccount("prefund_partner_004", "PAYOUT");

        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-5", "prefund_partner_004")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fundingModel\":\"PREFUNDED\","
                                + "\"floatTopUpBankAccountId\":" + payoutAccountId + "}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /v1/admin/partners/{code}/prefunding-config rehydrates the saved state")
    void getPrefundingConfig_returnsSavedState() throws Exception {
        createDraft("prefund_partner_005");
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-5", "prefund_partner_005")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(STEP5_BODY))
                .andExpect(status().isOk());

        mvc.perform(get("/v1/admin/partners/{code}/prefunding-config", "prefund_partner_005"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fundingModel").value("PREFUNDED"))
                .andExpect(jsonPath("$.lowBalanceThresholdUsd").value("25000.5000"));
    }

    @Test
    @DisplayName("GET prefunding-config before any step-5 save returns 404")
    void getConfig_noRowYet_404() throws Exception {
        createDraft("prefund_partner_006");
        mvc.perform(get("/v1/admin/partners/{code}/prefunding-config", "prefund_partner_006"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PATCH with a bad funding model returns 400")
    void patchStep5_badModel_400() throws Exception {
        createDraft("prefund_partner_007");
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-5", "prefund_partner_007")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fundingModel\":\"PAY_LATER\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH with a pattern missing {partner_code} returns 400")
    void patchStep5_badPattern_400() throws Exception {
        createDraft("prefund_partner_008");
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-5", "prefund_partner_008")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fundingModel\":\"PREFUNDED\","
                                + "\"topUpReferencePattern\":\"GMP-TOPUP-{yyyyMMdd}\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("unknown partner returns 404 on both endpoints")
    void unknownPartner_404() throws Exception {
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-5", "ghost_partner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(STEP5_BODY))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/admin/partners/{code}/prefunding-config", "ghost_partner"))
                .andExpect(status().isNotFound());
    }
}
