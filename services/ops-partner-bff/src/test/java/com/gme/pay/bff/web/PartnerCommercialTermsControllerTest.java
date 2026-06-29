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
import com.gme.pay.contracts.PartnerCommand;
import com.gme.pay.domain.PartnerType;
import java.math.RoundingMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Slice 6 (6B.1) MockMvc test for the BFF's commercial-terms pass-throughs on
 * {@link PartnerCommercialTermsController} —
 * {@code PATCH .../draft/{code}/step-6-commercial} and the four
 * per-sub-resource GETs. Uses the real {@link StubConfigRegistryClient} (the
 * same wiring the BFF runs when {@code gmepay.config-registry.client} is not
 * {@code rest}) so the round-trip create-draft → save commercial terms →
 * rehydrate is exercised end-to-end, mirroring
 * {@link PartnerPrefundingControllerTest}.
 *
 * <p>The 소액해외송금업 caps are pinned through the stub the same way
 * config-registry's {@code LimitsService} enforces them, so the Admin UI's
 * 400 path is exercised without booting config-registry.
 */
class PartnerCommercialTermsControllerTest {

    private MockMvc mvc;
    private StubConfigRegistryClient configRegistry;

    @BeforeEach
    void setUp() {
        configRegistry = new StubConfigRegistryClient();

        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mvc = standaloneSetup(new PartnerCommercialTermsController(configRegistry))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om))
                .build();
    }

    /** Seed a draft straight through the stub (the draft endpoints live on another controller). */
    private void createDraft(String partnerCode) {
        configRegistry.createDraft(new PartnerCommand.CreateDraft(
                partnerCode, PartnerType.OVERSEAS, "USD", RoundingMode.HALF_UP,
                null, "Clean Corp Ltd", null, null, "KR", null, null, null, null));
    }

    private static final String FULL_BODY = """
            {
              "feeSchedules": [
                {
                  "schemeId": "zeropay_kr",
                  "direction": "OUTBOUND",
                  "fixedFeeUsd": "1.50",
                  "bpsFee": "25",
                  "tiers": [{"fromVolumeUsd": "10000", "bpsOverride": "20"}]
                }
              ],
              "fxConfig": {
                "marginBps": "85",
                "referenceRateSource": "SEOUL_FX_BROKER",
                "quoteHoldSeconds": 600
              },
              "limits": {
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
    @DisplayName("PATCH /v1/admin/partners/draft/{code}/step-6-commercial saves all sections (money as strings)")
    void patchStep6Commercial_savesAndReturnsView() throws Exception {
        createDraft("comm_partner_001");

        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-6-commercial",
                        "comm_partner_001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(FULL_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feeSchedules[0].id").isNumber())
                .andExpect(jsonPath("$.feeSchedules[0].schemeId").value("zeropay_kr"))
                // MONEY_CONVENTION: decimal STRING on the wire, scale-4 normalized.
                .andExpect(jsonPath("$.feeSchedules[0].fixedFeeUsd").value("1.5000"))
                .andExpect(jsonPath("$.feeSchedules[0].tiers[0].bpsOverride").value("20.0000"))
                .andExpect(jsonPath("$.fxConfig.marginBps").value("85.0000"))
                .andExpect(jsonPath("$.fxConfig.quoteHoldSeconds").value(600))
                .andExpect(jsonPath("$.limits.perTxnMaxUsd").value("5000.0000"))
                .andExpect(jsonPath("$.limits.licenseType").value("SOAEK_HAEOEMONG"))
                .andExpect(jsonPath("$.contract.effectiveFrom").value("2026-07-01"))
                .andExpect(jsonPath("$.contract.autoRenewal").value(true));
    }

    @Test
    @DisplayName("PATCH with only the FX section leaves the other sections null")
    void patchStep6Commercial_partial_untouchedSectionsNull() throws Exception {
        createDraft("comm_partner_002");

        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-6-commercial",
                        "comm_partner_002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fxConfig\":{\"referenceRateSource\":\"MID_MARKET\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feeSchedules").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.fxConfig.referenceRateSource").value("MID_MARKET"))
                // Defaults mirrored by the stub (V019 column DEFAULTs).
                .andExpect(jsonPath("$.fxConfig.marginBps").value("0.0000"))
                .andExpect(jsonPath("$.fxConfig.quoteHoldSeconds").value(300))
                .andExpect(jsonPath("$.limits").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.contract").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @DisplayName("GETs per sub-resource rehydrate the saved state")
    void getSubResources_rehydrate() throws Exception {
        createDraft("comm_partner_003");
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-6-commercial",
                        "comm_partner_003")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(FULL_BODY))
                .andExpect(status().isOk());

        mvc.perform(get("/v1/admin/partners/{code}/fee-schedules", "comm_partner_003"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].schemeId").value("zeropay_kr"));
        mvc.perform(get("/v1/admin/partners/{code}/fx-config", "comm_partner_003"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referenceRateSource").value("SEOUL_FX_BROKER"));
        mvc.perform(get("/v1/admin/partners/{code}/limits", "comm_partner_003"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.annualCapUsd").value("50000.0000"));
        mvc.perform(get("/v1/admin/partners/{code}/contract", "comm_partner_003"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refundChargebackPolicy").value("SHARED"));
    }

    @Test
    @DisplayName("소액해외송금업 cap breach (perTxnMax 5001) rejects with 400 through the stub")
    void soaekCapBreach_400() throws Exception {
        createDraft("comm_partner_004");

        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-6-commercial",
                        "comm_partner_004")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"limits": {"perTxnMaxUsd": "5001",
                                            "annualCapUsd": "50000",
                                            "licenseType": "SOAEK_HAEOEMONG"}}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("unknown reference rate source rejects with 400 through the stub")
    void badRateSource_400() throws Exception {
        createDraft("comm_partner_005");

        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-6-commercial",
                        "comm_partner_005")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fxConfig\":{\"referenceRateSource\":\"BLOOMBERG\"}}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("empty composite body returns 400")
    void emptyComposite_400() throws Exception {
        createDraft("comm_partner_006");
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-6-commercial",
                        "comm_partner_006")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("unknown partner returns 404 on the PATCH and the GETs")
    void unknownPartner_404() throws Exception {
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-6-commercial", "comm_ghost")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(FULL_BODY))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/admin/partners/{code}/fx-config", "comm_ghost"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PATCH /step-6-currency-split originates a real split and returns the split-aware view")
    void patchStep6CurrencySplit_setsRealSplit() throws Exception {
        createDraft("split_partner_001"); // seeded with settlementCurrency=USD

        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-6-currency-split",
                        "split_partner_001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"collectionCcy\":\"USD\",\"settleACcy\":\"KRW\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.collectionCcy").value("USD"))
                .andExpect(jsonPath("$.settleACcy").value("KRW"))
                // the four-field identity is unchanged
                .andExpect(jsonPath("$.settlementCurrency").value("USD"));
    }

    @Test
    @DisplayName("PATCH /step-6-currency-split on an unknown partner returns 404")
    void patchStep6CurrencySplit_unknownPartner_404() throws Exception {
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-6-currency-split", "split_ghost")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"collectionCcy\":\"USD\",\"settleACcy\":\"KRW\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET single-row sub-resources before any save return 404; fee list is empty")
    void getBeforeSave_emptyListAnd404s() throws Exception {
        createDraft("comm_partner_007");
        mvc.perform(get("/v1/admin/partners/{code}/fee-schedules", "comm_partner_007"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
        mvc.perform(get("/v1/admin/partners/{code}/limits", "comm_partner_007"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/admin/partners/{code}/contract", "comm_partner_007"))
                .andExpect(status().isNotFound());
    }
}
