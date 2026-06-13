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
 * Slice 8 Lane D — MockMvc tests for the BFF webhook-subscription
 * pass-through endpoints ({@link PartnerWebhookSubscriptionController}).
 */
class PartnerWebhookSubscriptionControllerTest {

    private MockMvc mvc;
    private StubConfigRegistryClient configRegistry;

    @BeforeEach
    void setUp() {
        configRegistry = new StubConfigRegistryClient();
        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mvc = standaloneSetup(new PartnerWebhookSubscriptionController(configRegistry))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om))
                .build();
    }

    private void createDraft(String code) {
        configRegistry.createDraft(new PartnerCommand.CreateDraft(
                code, PartnerType.OVERSEAS, "USD", RoundingMode.HALF_UP,
                null, "Corp", null, null, "KR", null, null, null, null));
    }

    @Test
    @DisplayName("PATCH step-8/webhook-subscription saves and returns view with status=DRAFT")
    void patchWebhookSubscription_savesAndReturns() throws Exception {
        createDraft("wh_partner_001");
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-8/webhook-subscription",
                        "wh_partner_001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subscription":{
                                  "url":"https://partner.example.com/webhook",
                                  "eventTypes":["payment.approved","payment.failed"],
                                  "environment":"SANDBOX"
                                }}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://partner.example.com/webhook"))
                .andExpect(jsonPath("$.environment").value("SANDBOX"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.eventTypes.length()").value(2));
    }

    @Test
    @DisplayName("GET webhook-subscription rehydrates the saved draft")
    void getWebhookSubscription_returnsSaved() throws Exception {
        createDraft("wh_partner_002");
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-8/webhook-subscription",
                        "wh_partner_002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subscription":{
                                  "url":"https://webhook.example.com/events",
                                  "environment":"PRODUCTION"
                                }}
                                """))
                .andExpect(status().isOk());

        mvc.perform(get("/v1/admin/partners/{code}/webhook-subscription", "wh_partner_002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].url").value("https://webhook.example.com/events"))
                .andExpect(jsonPath("$[0].environment").value("PRODUCTION"));
    }
}
