package com.gme.pay.bff.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * Slice 3 (3B.1) MockMvc test for the BFF's KYB pass-throughs on
 * {@link PartnerKybController} — {@code PATCH .../draft/{code}/step-3},
 * {@code GET .../{code}/kyb} and {@code POST .../{code}/kyb/screen}. Uses the
 * real {@link StubConfigRegistryClient} (the same wiring the BFF runs when
 * {@code gmepay.config-registry.client} is not {@code rest}) so the round-trip
 * create-draft → save step-3 → screen → read KYB is exercised end-to-end,
 * mirroring {@link PartnerContactsControllerTest}.
 */
class PartnerKybControllerTest {

    private MockMvc mvc;
    private StubConfigRegistryClient configRegistry;

    @BeforeEach
    void setUp() {
        configRegistry = new StubConfigRegistryClient();

        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mvc = standaloneSetup(new PartnerKybController(configRegistry))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om))
                .build();
    }

    /** Seed a draft straight through the stub (the draft endpoints live on another controller). */
    private void createDraft(String partnerCode, String legalNameRomanized) {
        configRegistry.createDraft(new PartnerCommand.CreateDraft(
                partnerCode, PartnerType.OVERSEAS, "USD", RoundingMode.HALF_UP,
                null, legalNameRomanized, null, null, "KR", null, null, null, null));
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
    @DisplayName("PATCH /v1/admin/partners/draft/{code}/step-3 saves and returns the KYB view")
    void patchStep3_savesAndReturnsView() throws Exception {
        createDraft("kyb_partner_001", "Clean Corp Ltd");

        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-3", "kyb_partner_001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(STEP3_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.riskRating").value("MEDIUM"))
                .andExpect(jsonPath("$.licenseNumber").value("RL-2026-0042"))
                .andExpect(jsonPath("$.uboList.length()").value(2))
                .andExpect(jsonPath("$.uboList[1].isPep").value(true))
                .andExpect(jsonPath("$.screeningStatus").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @DisplayName("GET /v1/admin/partners/{code}/kyb rehydrates the saved step-3 state")
    void getKyb_returnsSavedState() throws Exception {
        createDraft("kyb_partner_002", "Clean Corp Ltd");
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-3", "kyb_partner_002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(STEP3_BODY))
                .andExpect(status().isOk());

        mvc.perform(get("/v1/admin/partners/{code}/kyb", "kyb_partner_002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskRating").value("MEDIUM"))
                .andExpect(jsonPath("$.nextReviewDate").value("2027-06-01"))
                .andExpect(jsonPath("$.uboList[0].name").value("Hong Gil Dong"));
    }

    @Test
    @DisplayName("POST /v1/admin/partners/{code}/kyb/screen returns the verdict and keeps step-3 fields")
    void screen_sanctionedName_isHit() throws Exception {
        createDraft("kyb_partner_003", "Sanctioned Holdings PLC");
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-3", "kyb_partner_003")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(STEP3_BODY))
                .andExpect(status().isOk());

        mvc.perform(post("/v1/admin/partners/{code}/kyb/screen", "kyb_partner_003"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.screeningStatus").value("HIT"))
                .andExpect(jsonPath("$.screeningProviderRef").isNotEmpty())
                .andExpect(jsonPath("$.screenedAt").isNotEmpty())
                .andExpect(jsonPath("$.riskRating").value("MEDIUM"));

        // ... and a subsequent step-3 save carries the verdict forward.
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-3", "kyb_partner_003")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(STEP3_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.screeningStatus").value("HIT"));
    }

    @Test
    @DisplayName("screening a clean draft returns CLEAR even without a prior step-3 save")
    void screen_cleanDraft_isClear() throws Exception {
        createDraft("kyb_partner_004", "Totally Clean GmbH");

        mvc.perform(post("/v1/admin/partners/{code}/kyb/screen", "kyb_partner_004"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.screeningStatus").value("CLEAR"))
                .andExpect(jsonPath("$.riskRating").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @DisplayName("unknown partner returns 404 on all three endpoints")
    void unknownPartner_404() throws Exception {
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-3", "ghost_partner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(STEP3_BODY))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/admin/partners/{code}/kyb", "ghost_partner"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/v1/admin/partners/{code}/kyb/screen", "ghost_partner"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET kyb before any step-3 save returns 404")
    void getKyb_noRowYet_404() throws Exception {
        createDraft("kyb_partner_005", "Clean Corp Ltd");
        mvc.perform(get("/v1/admin/partners/{code}/kyb", "kyb_partner_005"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PATCH step-3 with a bad risk rating returns 400")
    void patchStep3_badRiskRating_400() throws Exception {
        createDraft("kyb_partner_006", "Clean Corp Ltd");
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-3", "kyb_partner_006")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"riskRating\":\"EXTREME\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH step-3 with an out-of-range UBO ownership returns 400")
    void patchStep3_badUboPct_400() throws Exception {
        createDraft("kyb_partner_007", "Clean Corp Ltd");
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-3", "kyb_partner_007")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"uboList":[{"name":"Kim","ownershipPct":150,"isPep":false,"country":"KR"}]}
                                """))
                .andExpect(status().isBadRequest());
    }
}
