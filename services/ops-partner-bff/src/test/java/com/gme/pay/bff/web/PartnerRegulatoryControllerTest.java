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
 * Slice 8 Lane C — MockMvc tests for the BFF regulatory pass-through
 * endpoints ({@link PartnerRegulatoryController}).
 */
class PartnerRegulatoryControllerTest {

    private MockMvc mvc;
    private StubConfigRegistryClient configRegistry;

    @BeforeEach
    void setUp() {
        configRegistry = new StubConfigRegistryClient();
        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mvc = standaloneSetup(new PartnerRegulatoryController(configRegistry))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om))
                .build();
    }

    private void createDraft(String code) {
        configRegistry.createDraft(new PartnerCommand.CreateDraft(
                code, PartnerType.OVERSEAS, "USD", RoundingMode.HALF_UP,
                null, "Corp", null, null, "KR", null, null, null, null));
    }

    @Test
    @DisplayName("PATCH step-8/regulatory saves and returns the config")
    void patchRegulatory_savesAndReturns() throws Exception {
        createDraft("reg_partner_001");
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-8/regulatory",
                        "reg_partner_001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"regulatory":{
                                  "bokTxnCode":"101",
                                  "kofiuEntityId":"KFU-001",
                                  "ctrThresholdKrw":"10000000",
                                  "travelRuleProtocol":"NONE"
                                }}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kofiuEntityId").value("KFU-001"))
                .andExpect(jsonPath("$.bokTxnCode").value("101"))
                .andExpect(jsonPath("$.ctrThresholdKrw").value("10000000"));
    }

    @Test
    @DisplayName("GET regulatory rehydrates the saved config")
    void getRegulatory_returnsSaved() throws Exception {
        createDraft("reg_partner_002");
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-8/regulatory",
                        "reg_partner_002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"regulatory":{
                                  "travelRuleProtocol":"NONE",
                                  "kofiuEntityId":"KFU-002"
                                }}
                                """))
                .andExpect(status().isOk());

        mvc.perform(get("/v1/admin/partners/{code}/regulatory", "reg_partner_002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kofiuEntityId").value("KFU-002"));
    }

    @Test
    @DisplayName("GET regulatory returns 404 when no step-8 save yet")
    void getRegulatory_notYetSaved_404() throws Exception {
        createDraft("reg_partner_003");
        mvc.perform(get("/v1/admin/partners/{code}/regulatory", "reg_partner_003"))
                .andExpect(status().isNotFound());
    }
}
