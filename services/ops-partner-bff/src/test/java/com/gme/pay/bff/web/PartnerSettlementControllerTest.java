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
 * Slice 4 (4B.1) MockMvc test for the BFF's settlement-config pass-throughs on
 * {@link PartnerSettlementController} —
 * {@code PATCH .../draft/{code}/step-4-settlement},
 * {@code GET .../{code}/settlement-config} and
 * {@code GET .../{code}/settlement-preview}. Uses the real
 * {@link StubConfigRegistryClient} (the same wiring the BFF runs when
 * {@code gmepay.config-registry.client} is not {@code rest}) so the round-trip
 * create-draft → save settlement → preview is exercised end-to-end, mirroring
 * {@link PartnerKybControllerTest}.
 *
 * <p>NOTE: the stub's preview skips WEEKENDS only — the holiday-calendar math
 * (V014, Chuseok / cross-country unions) is pinned by config-registry's
 * {@code SettlementConfigServiceTest} and
 * {@code SettlementScheduleCalculatorTest}; here the weekend roll proves the
 * pass-through plumbing.
 */
class PartnerSettlementControllerTest {

    private MockMvc mvc;
    private StubConfigRegistryClient configRegistry;

    @BeforeEach
    void setUp() {
        configRegistry = new StubConfigRegistryClient();

        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mvc = standaloneSetup(new PartnerSettlementController(configRegistry))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om))
                .build();
    }

    /** Seed a draft straight through the stub (the draft endpoints live on another controller). */
    private void createDraft(String partnerCode) {
        configRegistry.createDraft(new PartnerCommand.CreateDraft(
                partnerCode, PartnerType.OVERSEAS, "USD", RoundingMode.HALF_UP,
                null, "Clean Corp Ltd", null, null, "KR", null, null, null, null));
    }

    private static final String STEP4_BODY = """
            {
              "cycleTPlusN": 1,
              "cutoffTime": "16:30",
              "cutoffTimezone": "Asia/Seoul",
              "settlementMethod": "KR_FIRM_BANKING"
            }
            """;

    @Test
    @DisplayName("PATCH /v1/admin/partners/draft/{code}/step-4-settlement saves and returns the view")
    void patchStep4Settlement_savesAndReturnsView() throws Exception {
        createDraft("settle_partner_001");

        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-4-settlement",
                        "settle_partner_001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(STEP4_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.cycleTPlusN").value(1))
                .andExpect(jsonPath("$.cutoffTime").value("16:30:00"))
                .andExpect(jsonPath("$.cutoffTimezone").value("Asia/Seoul"))
                .andExpect(jsonPath("$.settlementMethod").value("KR_FIRM_BANKING"));
    }

    @Test
    @DisplayName("PATCH with nulls applies the V013 defaults (mirrored by the stub)")
    void patchStep4Settlement_appliesDefaults() throws Exception {
        createDraft("settle_partner_002");

        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-4-settlement",
                        "settle_partner_002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settlementMethod\":\"SWIFT_MT103\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cycleTPlusN").value(1))
                .andExpect(jsonPath("$.cutoffTime").value("16:30:00"))
                .andExpect(jsonPath("$.cutoffTimezone").value("Asia/Seoul"));
    }

    @Test
    @DisplayName("GET /v1/admin/partners/{code}/settlement-config rehydrates the saved state")
    void getSettlementConfig_returnsSavedState() throws Exception {
        createDraft("settle_partner_003");
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-4-settlement",
                        "settle_partner_003")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(STEP4_BODY))
                .andExpect(status().isOk());

        mvc.perform(get("/v1/admin/partners/{code}/settlement-config", "settle_partner_003"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settlementMethod").value("KR_FIRM_BANKING"))
                .andExpect(jsonPath("$.cutoffTime").value("16:30:00"));
    }

    @Test
    @DisplayName("GET settlement-preview rolls a Friday T+1 over the weekend to Monday")
    void getSettlementPreview_weekendRoll() throws Exception {
        createDraft("settle_partner_004");
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-4-settlement",
                        "settle_partner_004")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(STEP4_BODY))
                .andExpect(status().isOk());

        // Fri 2026-06-12 10:00 KST (within cutoff) + T+1 -> Mon 2026-06-15.
        mvc.perform(get("/v1/admin/partners/{code}/settlement-preview", "settle_partner_004")
                        .param("txnInstant", "2026-06-12T01:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payoutDate").value("2026-06-15"))
                .andExpect(jsonPath("$.explanation").isArray())
                .andExpect(jsonPath("$.explanation",
                        org.hamcrest.Matchers.hasItem(
                                org.hamcrest.Matchers.containsString("SATURDAY"))));
    }

    @Test
    @DisplayName("GET settlement-preview before any settlement save returns 404")
    void preview_noConfigYet_404() throws Exception {
        createDraft("settle_partner_005");
        mvc.perform(get("/v1/admin/partners/{code}/settlement-preview", "settle_partner_005")
                        .param("txnInstant", "2026-06-12T01:00:00Z"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("malformed txnInstant returns 400")
    void preview_badTxnInstant_400() throws Exception {
        createDraft("settle_partner_006");
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-4-settlement",
                        "settle_partner_006")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(STEP4_BODY))
                .andExpect(status().isOk());

        mvc.perform(get("/v1/admin/partners/{code}/settlement-preview", "settle_partner_006")
                        .param("txnInstant", "next tuesday"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH with a bad settlement method returns 400")
    void patchStep4Settlement_badMethod_400() throws Exception {
        createDraft("settle_partner_007");
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-4-settlement",
                        "settle_partner_007")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settlementMethod\":\"CARRIER_PIGEON\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("unknown partner returns 404 on all three endpoints")
    void unknownPartner_404() throws Exception {
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-4-settlement", "ghost_partner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(STEP4_BODY))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/admin/partners/{code}/settlement-config", "ghost_partner"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/admin/partners/{code}/settlement-preview", "ghost_partner")
                        .param("txnInstant", "2026-06-12T01:00:00Z"))
                .andExpect(status().isNotFound());
    }
}
