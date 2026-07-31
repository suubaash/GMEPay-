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
import com.gme.pay.bff.security.TestTokens;
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
        // enforce=false: the pass-through tests below are about the KYB round-trip, not RBAC. The
        // attestation endpoint's own gate is covered separately with enforce=true.
        mvc = standaloneSetup(new PartnerKybController(configRegistry, new OpsRbacGuard(false)))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om))
                .build();
    }

    @org.junit.jupiter.api.AfterEach
    void clearAuthentication() {
        TestTokens.clear();
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
    @DisplayName("T1-4: screening a clean draft through the stub reports NOTHING SCREENED, not CLEAR")
    void screen_cleanDraft_isNotScreened() throws Exception {
        // Was `CLEAR` until 2026-07-28. The BFF fallback client consults no sanctions, PEP or
        // adverse-media source — it matches the subject's own names against two tokens — so a
        // `CLEAR` here was a clean screening nobody performed, and it was the last place in the
        // platform that could still mint one (lib-kyb + V042 closed the real path).
        createDraft("kyb_partner_004", "Totally Clean GmbH");

        mvc.perform(post("/v1/admin/partners/{code}/kyb/screen", "kyb_partner_004"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.screeningStatus").value("NOT_SCREENED_NO_PROVIDER"))
                .andExpect(jsonPath("$.riskRating").value(org.hamcrest.Matchers.nullValue()));
    }

    // ------------------------------ T1-4: manual KYB SOP attestation ------------------------

    private static final String ATTEST_BODY = """
            {
              "outcome": "CLEAR",
              "sopDocumentRef": "GME-COMP-SOP-014 Manual sanctions screening",
              "sopVersion": "v3",
              "sourcesConsulted": "UN consolidated list + the SOP §4 jurisdiction lists, searched by\
             romanized and local legal name plus every declared UBO",
              "attestation": "I performed this sanctions and PEP screening myself, following the SOP\
             named above, and I am accountable for the result."
            }
            """;

    @Test
    @DisplayName("an attested manual screening is recorded as CLEAR_MANUAL_ATTESTATION, not CLEAR")
    void manualAttestation_isDistinguishableFromAVendorClear() throws Exception {
        createDraft("kyb_partner_008", "Totally Clean GmbH");
        TestTokens.hubOperator("ops:operate");

        mvc.perform(post("/v1/admin/partners/{code}/kyb/manual-screening-attestation",
                        "kyb_partner_008")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ATTEST_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.screeningStatus").value("CLEAR_MANUAL_ATTESTATION"))
                .andExpect(jsonPath("$.screeningProviderRef").value(
                        org.hamcrest.Matchers.containsString("manual-sop")));

        // ...and the provenance read carries the detail behind that distinction.
        mvc.perform(get("/v1/admin/partners/{code}/kyb/screening-provenance", "kyb_partner_008"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.screeningStatus").value("CLEAR_MANUAL_ATTESTATION"))
                .andExpect(jsonPath("$.providerId").value("manual-sop"))
                .andExpect(jsonPath("$.manuallyAttested").value(true))
                .andExpect(jsonPath("$.satisfiesActivation").value(true))
                .andExpect(jsonPath("$.manualAttestation.attesterActorId")
                        .value(TestTokens.DEFAULT_SUBJECT))
                .andExpect(jsonPath("$.manualAttestation.sopVersion").value("v3"))
                .andExpect(jsonPath("$.manualAttestation.complete").value(true))
                .andExpect(jsonPath("$.interpretation").value(
                        org.hamcrest.Matchers.containsString("NOT a vendor screening")));
    }

    @Test
    @DisplayName("an unscreened partner's provenance says nothing was screened and does not activate")
    void unscreenedProvenanceIsExplicit() throws Exception {
        createDraft("kyb_partner_009", "Totally Clean GmbH");
        mvc.perform(post("/v1/admin/partners/{code}/kyb/screen", "kyb_partner_009"))
                .andExpect(status().isOk());

        mvc.perform(get("/v1/admin/partners/{code}/kyb/screening-provenance", "kyb_partner_009"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manuallyAttested").value(false))
                .andExpect(jsonPath("$.authoritative").value(false))
                .andExpect(jsonPath("$.satisfiesActivation").value(false))
                .andExpect(jsonPath("$.manualAttestation").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.interpretation").value(
                        org.hamcrest.Matchers.containsString("NOTHING WAS SCREENED")));
    }

    @Test
    @DisplayName("recording an attestation requires ops:operate — an onboarding-write token is not enough")
    void manualAttestation_requiresOpsOperate() throws Exception {
        createDraft("kyb_partner_010", "Totally Clean GmbH");
        MockMvc enforcing = standaloneSetup(
                new PartnerKybController(configRegistry, new OpsRbacGuard(true)))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        new ObjectMapper().registerModule(new JavaTimeModule())))
                .build();

        // A token that may edit partners but is not an ops operator.
        TestTokens.hubOperator("partner.view");
        enforcing.perform(post("/v1/admin/partners/{code}/kyb/manual-screening-attestation",
                        "kyb_partner_010")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ATTEST_BODY))
                .andExpect(status().isForbidden());

        // No permissions at all: still refused when enforcing.
        TestTokens.clear();
        enforcing.perform(post("/v1/admin/partners/{code}/kyb/manual-screening-attestation",
                        "kyb_partner_010")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ATTEST_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an attestation missing its SOP reference, version, sources or assertion is 400")
    void manualAttestation_requiresTheEvidence() throws Exception {
        createDraft("kyb_partner_011", "Totally Clean GmbH");
        TestTokens.hubOperator("ops:operate");

        for (String body : new String[] {
                ATTEST_BODY.replace("\"GME-COMP-SOP-014 Manual sanctions screening\"", "\"\""),
                ATTEST_BODY.replace("\"v3\"", "\"  \""),
                ATTEST_BODY.replaceAll("\"sourcesConsulted\": \"[^\"]*\"",
                        "\"sourcesConsulted\": \"\""),
                ATTEST_BODY.replaceAll("\"attestation\": \"[^\"]*\"", "\"attestation\": \"yes\""),
                ATTEST_BODY.replace("\"CLEAR\"", "\"NOT_SCREENED_NO_PROVIDER\"")}) {
            mvc.perform(post("/v1/admin/partners/{code}/kyb/manual-screening-attestation",
                            "kyb_partner_011")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
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
