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
 * Slice 7 MockMvc tests for the BFF's scheme-enablement and corridor
 * pass-throughs on {@link PartnerSchemesController} —
 * {@code PATCH .../draft/{code}/step-7/schemes},
 * {@code PATCH .../draft/{code}/step-7/corridors},
 * {@code GET .../{code}/schemes},
 * {@code GET .../{code}/corridors}, and
 * {@code GET .../schemes/{schemeId}/operating-hours}.
 *
 * <p>Uses the real {@link StubConfigRegistryClient} (same wiring the BFF runs
 * when {@code gmepay.config-registry.client} is not {@code rest}) so the
 * round-trip create-draft → save → rehydrate is exercised end-to-end, mirroring
 * {@link PartnerCommercialTermsControllerTest}.
 */
class PartnerSchemesControllerTest {

    private MockMvc mvc;
    private StubConfigRegistryClient configRegistry;

    @BeforeEach
    void setUp() {
        configRegistry = new StubConfigRegistryClient();
        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mvc = standaloneSetup(new PartnerSchemesController(configRegistry))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om))
                .build();
    }

    /** Seed a draft straight through the stub. */
    private void createDraft(String partnerCode) {
        configRegistry.createDraft(new PartnerCommand.CreateDraft(
                partnerCode, PartnerType.OVERSEAS, "USD", RoundingMode.HALF_UP,
                null, "Test Corp", null, null, "KR", null, null, null, null));
    }

    // -----------------------------------------------------------------------
    // step-7/schemes PATCH
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("PATCH /v1/admin/partners/draft/{code}/step-7/schemes saves and returns the set")
    void patchStep7Schemes_savesAndReturns() throws Exception {
        createDraft("scheme_partner_001");

        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-7/schemes",
                        "scheme_partner_001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"schemes":[{
                                  "schemeId":"ZEROPAY",
                                  "direction":"OUTBOUND",
                                  "role":"ACQUIRER",
                                  "zeropayMerchantId":"ZP-001",
                                  "kftcInstitutionCode":"KFTC-001",
                                  "enabled":true
                                }]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].schemeId").value("ZEROPAY"))
                .andExpect(jsonPath("$[0].direction").value("OUTBOUND"))
                .andExpect(jsonPath("$[0].role").value("ACQUIRER"))
                .andExpect(jsonPath("$[0].zeropayMerchantId").value("ZP-001"))
                .andExpect(jsonPath("$[0].kftcInstitutionCode").value("KFTC-001"))
                .andExpect(jsonPath("$[0].enabled").value(true));
    }

    @Test
    @DisplayName("GET /v1/admin/partners/{code}/schemes rehydrates the saved set")
    void getSchemes_returnsSavedState() throws Exception {
        createDraft("scheme_partner_002");
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-7/schemes",
                        "scheme_partner_002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"schemes":[{
                                  "schemeId":"BAKONG",
                                  "direction":"BOTH",
                                  "role":"ISSUER",
                                  "enabled":false
                                }]}
                                """))
                .andExpect(status().isOk());

        mvc.perform(get("/v1/admin/partners/{code}/schemes", "scheme_partner_002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].schemeId").value("BAKONG"))
                .andExpect(jsonPath("$[0].enabled").value(false));
    }

    @Test
    @DisplayName("GET schemes before any save returns an empty list")
    void getSchemes_noSaveYet_emptyList() throws Exception {
        createDraft("scheme_partner_003");
        mvc.perform(get("/v1/admin/partners/{code}/schemes", "scheme_partner_003"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("enabled ZEROPAY without zeropayMerchantId returns 400")
    void zeropayMissingMerchantId_400() throws Exception {
        createDraft("scheme_partner_004");
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-7/schemes",
                        "scheme_partner_004")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"schemes":[{
                                  "schemeId":"ZEROPAY",
                                  "direction":"OUTBOUND",
                                  "role":"ACQUIRER",
                                  "kftcInstitutionCode":"KFTC-001",
                                  "enabled":true
                                }]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("bad schemeId returns 400")
    void badSchemeId_400() throws Exception {
        createDraft("scheme_partner_005");
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-7/schemes",
                        "scheme_partner_005")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"schemes":[{
                                  "schemeId":"PAYTM",
                                  "direction":"OUTBOUND",
                                  "role":"ACQUIRER"
                                }]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("duplicate schemeId in payload returns 400")
    void duplicateSchemeId_400() throws Exception {
        createDraft("scheme_partner_006");
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-7/schemes",
                        "scheme_partner_006")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"schemes":[
                                  {"schemeId":"BAKONG","direction":"OUTBOUND","role":"ACQUIRER"},
                                  {"schemeId":"BAKONG","direction":"INBOUND","role":"ISSUER"}
                                ]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("unknown partner returns 404 on PATCH and GET schemes")
    void unknownPartner_schemes_404() throws Exception {
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-7/schemes", "ghost")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"schemes":[]}
                                """))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/admin/partners/{code}/schemes", "ghost"))
                .andExpect(status().isNotFound());
    }

    // -----------------------------------------------------------------------
    // step-7/corridors PATCH
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("PATCH /v1/admin/partners/draft/{code}/step-7/corridors saves and returns the set")
    void patchStep7Corridors_savesAndReturns() throws Exception {
        createDraft("corridor_partner_001");

        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-7/corridors",
                        "corridor_partner_001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"corridors":[{
                                  "srcCountry":"KR","srcCcy":"KRW",
                                  "dstCountry":"MN","dstCcy":"MNT",
                                  "goLiveDate":"2026-10-01",
                                  "isActive":true
                                }]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].srcCountry").value("KR"))
                .andExpect(jsonPath("$[0].srcCcy").value("KRW"))
                .andExpect(jsonPath("$[0].dstCountry").value("MN"))
                .andExpect(jsonPath("$[0].dstCcy").value("MNT"))
                .andExpect(jsonPath("$[0].goLiveDate").value("2026-10-01"))
                .andExpect(jsonPath("$[0].isActive").value(true));
    }

    @Test
    @DisplayName("GET /v1/admin/partners/{code}/corridors rehydrates the saved set")
    void getCorridors_returnsSavedState() throws Exception {
        createDraft("corridor_partner_002");
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-7/corridors",
                        "corridor_partner_002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"corridors":[{
                                  "srcCountry":"KR","srcCcy":"KRW",
                                  "dstCountry":"SG","dstCcy":"SGD",
                                  "isActive":true
                                }]}
                                """))
                .andExpect(status().isOk());

        mvc.perform(get("/v1/admin/partners/{code}/corridors", "corridor_partner_002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].dstCountry").value("SG"));
    }

    @Test
    @DisplayName("bad country code returns 400")
    void badCountryCode_400() throws Exception {
        createDraft("corridor_partner_003");
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-7/corridors",
                        "corridor_partner_003")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"corridors":[{
                                  "srcCountry":"Korea","srcCcy":"KRW",
                                  "dstCountry":"MN","dstCcy":"MNT"
                                }]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("duplicate lane key returns 400")
    void duplicateLane_400() throws Exception {
        createDraft("corridor_partner_004");
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-7/corridors",
                        "corridor_partner_004")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"corridors":[
                                  {"srcCountry":"KR","srcCcy":"KRW",
                                   "dstCountry":"MN","dstCcy":"MNT"},
                                  {"srcCountry":"KR","srcCcy":"KRW",
                                   "dstCountry":"MN","dstCcy":"MNT"}
                                ]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("unknown partner returns 404 on PATCH and GET corridors")
    void unknownPartner_corridors_404() throws Exception {
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-7/corridors", "ghost")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"corridors":[]}
                                """))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/admin/partners/{code}/corridors", "ghost"))
                .andExpect(status().isNotFound());
    }

    // -----------------------------------------------------------------------
    // GET /v1/admin/schemes/{schemeId}/operating-hours
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("GET /v1/admin/schemes/ZEROPAY/operating-hours returns seed rows")
    void getOperatingHours_zeropay_returnsSeedRows() throws Exception {
        mvc.perform(get("/v1/admin/schemes/{id}/operating-hours", "ZEROPAY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$[0].schemeId").value("ZEROPAY"))
                .andExpect(jsonPath("$[0].weekday").value(0))
                .andExpect(jsonPath("$[0].timezone").value("Asia/Seoul"))
                .andExpect(jsonPath("$[0].openTimeLocal").value("09:00:00"))
                .andExpect(jsonPath("$[0].cutoffTimeLocal").value("21:00:00"));
    }

    @Test
    @DisplayName("GET /v1/admin/schemes/FAST_SG/operating-hours returns 7 rows (24x7)")
    void getOperatingHours_fastSg_returns7Rows() throws Exception {
        mvc.perform(get("/v1/admin/schemes/{id}/operating-hours", "FAST_SG"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(7))
                .andExpect(jsonPath("$[0].timezone").value("Asia/Singapore"));
    }

    @Test
    @DisplayName("GET /v1/admin/schemes/UNKNOWN_SCHEME/operating-hours returns empty list (not 404)")
    void getOperatingHours_unknownScheme_emptyList() throws Exception {
        mvc.perform(get("/v1/admin/schemes/{id}/operating-hours", "UNKNOWN_SCHEME"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
