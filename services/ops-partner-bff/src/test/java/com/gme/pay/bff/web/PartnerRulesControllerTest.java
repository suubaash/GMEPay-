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
 * Slice 6 (6A.1) MockMvc test for the BFF's pricing-rule pass-throughs on
 * {@link PartnerRulesController} —
 * {@code PATCH .../draft/{code}/step-6-rules} and {@code GET .../{code}/rules}.
 * Uses the real {@link StubConfigRegistryClient} (the same wiring the BFF runs
 * when {@code gmepay.config-registry.client} is not {@code rest}) so the
 * round-trip create-draft → save rules → rehydrate is exercised end-to-end,
 * mirroring {@link PartnerPrefundingControllerTest}.
 *
 * <p>The lib-domain margin invariant is pinned through the stub: drafts
 * created with a single settlement currency carry the V016 mirror split
 * (collect USD / settle USD), so a same-currency rule with a non-zero margin
 * is a 400 — the same verdict config-registry's {@code RuleService} returns.
 */
class PartnerRulesControllerTest {

    private MockMvc mvc;
    private StubConfigRegistryClient configRegistry;

    @BeforeEach
    void setUp() {
        configRegistry = new StubConfigRegistryClient();

        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mvc = standaloneSetup(new PartnerRulesController(configRegistry))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om))
                .build();
    }

    /** Seed a draft straight through the stub (the draft endpoints live on another controller). */
    private void createDraft(String partnerCode) {
        configRegistry.createDraft(new PartnerCommand.CreateDraft(
                partnerCode, PartnerType.OVERSEAS, "USD", RoundingMode.HALF_UP,
                null, "Clean Corp Ltd", null, null, "KR", null, null, null, null));
    }

    /** Same-currency shape (the draft's V016 mirror split is USD/USD): zero margins + flat charge. */
    private static final String STEP6_RULES_BODY = """
            {
              "rules": [
                {
                  "schemeId": "ZEROPAY",
                  "direction": "OUTBOUND",
                  "mA": "0",
                  "mB": "0",
                  "serviceChargeUsd": "3.50"
                }
              ]
            }
            """;

    @Test
    @DisplayName("PATCH /v1/admin/partners/draft/{code}/step-6-rules saves and returns the set (decimals as strings)")
    void patchStep6Rules_savesAndReturnsViews() throws Exception {
        createDraft("rule_partner_001");

        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-6-rules", "rule_partner_001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(STEP6_RULES_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").isNumber())
                .andExpect(jsonPath("$[0].schemeId").value("ZEROPAY"))
                .andExpect(jsonPath("$[0].direction").value("OUTBOUND"))
                // MONEY_CONVENTION: decimal STRING on the wire, scale-4 normalized.
                .andExpect(jsonPath("$[0].mA").value("0.0000"))
                .andExpect(jsonPath("$[0].serviceChargeUsd").value("3.5000"));
    }

    @Test
    @DisplayName("GET /v1/admin/partners/{code}/rules rehydrates the saved set")
    void getRules_returnsSavedState() throws Exception {
        createDraft("rule_partner_002");
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-6-rules", "rule_partner_002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(STEP6_RULES_BODY))
                .andExpect(status().isOk());

        mvc.perform(get("/v1/admin/partners/{code}/rules", "rule_partner_002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].schemeId").value("ZEROPAY"));
    }

    @Test
    @DisplayName("GET rules before any step-6 save returns an empty list")
    void getRules_noSaveYet_emptyList() throws Exception {
        createDraft("rule_partner_003");
        mvc.perform(get("/v1/admin/partners/{code}/rules", "rule_partner_003"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("same-currency rule with a non-zero margin returns 400 (lib-domain invariant via the stub)")
    void sameCurrencyNonZeroMargin_400() throws Exception {
        createDraft("rule_partner_004");
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-6-rules", "rule_partner_004")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rules":[{"schemeId":"ZEROPAY","direction":"OUTBOUND",
                                           "mA":"0.01","mB":"0.01"}]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("bad direction returns 400")
    void badDirection_400() throws Exception {
        createDraft("rule_partner_005");
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-6-rules", "rule_partner_005")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rules":[{"schemeId":"ZEROPAY","direction":"SIDEWAYS",
                                           "mA":"0","mB":"0"}]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("duplicate (scheme, direction) keys return 400")
    void duplicateKeys_400() throws Exception {
        createDraft("rule_partner_006");
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-6-rules", "rule_partner_006")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rules":[
                                  {"schemeId":"ZEROPAY","direction":"OUTBOUND","mA":"0","mB":"0"},
                                  {"schemeId":"ZEROPAY","direction":"OUTBOUND","mA":"0","mB":"0"}
                                ]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("missing rules list returns 400")
    void nullRules_400() throws Exception {
        createDraft("rule_partner_007");
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-6-rules", "rule_partner_007")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("unknown partner returns 404 on both endpoints")
    void unknownPartner_404() throws Exception {
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-6-rules", "ghost_partner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(STEP6_RULES_BODY))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/admin/partners/{code}/rules", "ghost_partner"))
                .andExpect(status().isNotFound());
    }
}
