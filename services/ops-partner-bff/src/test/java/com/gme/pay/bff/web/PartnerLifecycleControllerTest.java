package com.gme.pay.bff.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * Slice 8 Lane A — MockMvc tests for the BFF lifecycle pass-through endpoints.
 * Uses the real {@link StubConfigRegistryClient} so the full route
 * controller → client → stub is exercised end-to-end.
 */
class PartnerLifecycleControllerTest {

    private MockMvc mvc;
    private StubConfigRegistryClient configRegistry;

    @BeforeEach
    void setUp() {
        configRegistry = new StubConfigRegistryClient();
        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mvc = standaloneSetup(new PartnerLifecycleController(configRegistry))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om))
                .build();
    }

    private void createDraft(String code) {
        configRegistry.createDraft(new PartnerCommand.CreateDraft(
                code, PartnerType.OVERSEAS, "USD", RoundingMode.HALF_UP,
                null, "Corp", null, null, "KR", null, null, null, null));
    }

    // -----------------------------------------------------------------------
    // activate
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("POST /activate on known partner returns 200 with partner view")
    void activate_knownPartner_200() throws Exception {
        createDraft("lc_partner_001");
        mvc.perform(post("/v1/admin/partners/{code}/lifecycle/activate", "lc_partner_001")
                        .header("X-Actor", "operator_01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partnerCode").value("lc_partner_001"));
    }

    @Test
    @DisplayName("POST /activate on unknown partner returns 404")
    void activate_unknownPartner_404() throws Exception {
        mvc.perform(post("/v1/admin/partners/{code}/lifecycle/activate", "ghost_001")
                        .header("X-Actor", "operator_01"))
                .andExpect(status().isNotFound());
    }

    // -----------------------------------------------------------------------
    // suspend
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("POST /suspend with reason returns 200 with partner view")
    void suspend_withReason_200() throws Exception {
        createDraft("lc_partner_002");
        mvc.perform(post("/v1/admin/partners/{code}/lifecycle/suspend", "lc_partner_002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"AMLCFT_CONCERN","notes":"Unusual pattern"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partnerCode").value("lc_partner_002"));
    }

    @Test
    @DisplayName("POST /suspend without reason returns 400")
    void suspend_missingReason_400() throws Exception {
        createDraft("lc_partner_003");
        mvc.perform(post("/v1/admin/partners/{code}/lifecycle/suspend", "lc_partner_003")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"notes":"No reason given"}
                                """))
                .andExpect(status().isBadRequest());
    }

    // -----------------------------------------------------------------------
    // reactivate
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("POST /reactivate on known partner returns 200 with partner view")
    void reactivate_knownPartner_200() throws Exception {
        createDraft("lc_partner_004");
        mvc.perform(post("/v1/admin/partners/{code}/lifecycle/reactivate", "lc_partner_004")
                        .header("X-Actor", "operator_01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partnerCode").value("lc_partner_004"));
    }

    // -----------------------------------------------------------------------
    // terminate
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("POST /terminate with reason returns 200 with partner view")
    void terminate_withReason_200() throws Exception {
        createDraft("lc_partner_005");
        mvc.perform(post("/v1/admin/partners/{code}/lifecycle/terminate", "lc_partner_005")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"Contract expired"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partnerCode").value("lc_partner_005"));
    }

    // -----------------------------------------------------------------------
    // preconditions
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("GET /preconditions on known partner returns 200 with passes=true (stub)")
    void preconditions_knownPartner_200() throws Exception {
        createDraft("lc_partner_006");
        mvc.perform(get("/v1/admin/partners/{code}/lifecycle/preconditions", "lc_partner_006"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passes").value(true))
                .andExpect(jsonPath("$.unmet").isArray())
                .andExpect(jsonPath("$.unmet.length()").value(0));
    }
}
