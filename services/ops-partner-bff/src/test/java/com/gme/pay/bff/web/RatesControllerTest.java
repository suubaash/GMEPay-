package com.gme.pay.bff.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gme.pay.bff.client.stub.StubRatesClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Standalone MockMvc test for {@link RatesController}. Uses the real
 * {@link StubRatesClient} so the 5-step USD-pivot math is exercised end-to-end.
 */
class RatesControllerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        RatesController controller = new RatesController(new StubRatesClient());

        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter(om);

        mvc = standaloneSetup(controller).setMessageConverters(converter).build();
    }

    @Test
    @DisplayName("POST /v1/admin/rates/preview returns a fully populated preview for KRW -> USD")
    void preview_krwToUsd_returnsPopulatedFields() throws Exception {
        String body = """
                {"fromCcy":"KRW","toCcy":"USD","amount":"1000000","direction":"COLLECTION","partnerId":1}
                """;
        mvc.perform(post("/v1/admin/rates/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.collectionCurrency").value("KRW"))
                .andExpect(jsonPath("$.payoutCurrency").value("USD"))
                .andExpect(jsonPath("$.collectionAmount").exists())
                .andExpect(jsonPath("$.payoutAmount").exists())
                .andExpect(jsonPath("$.collectionUsd").exists())
                .andExpect(jsonPath("$.payoutUsdCost").exists())
                .andExpect(jsonPath("$.collectionMarginUsd").exists())
                .andExpect(jsonPath("$.payoutMarginUsd").exists())
                .andExpect(jsonPath("$.offerRateColl").exists())
                .andExpect(jsonPath("$.crossRate").exists())
                .andExpect(jsonPath("$.shortCircuit").value(false))
                .andExpect(jsonPath("$.quotedAt").exists());
    }

    @Test
    @DisplayName("POST /v1/admin/rates/preview short-circuits on same-currency requests")
    void preview_sameCurrency_shortCircuits() throws Exception {
        String body = """
                {"fromCcy":"USD","toCcy":"USD","amount":"100.00","direction":"COLLECTION","partnerId":1}
                """;
        mvc.perform(post("/v1/admin/rates/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shortCircuit").value(true))
                .andExpect(jsonPath("$.collectionAmount").value(100.00))
                .andExpect(jsonPath("$.payoutAmount").value(100.00))
                .andExpect(jsonPath("$.collectionMarginUsd").value(0))
                .andExpect(jsonPath("$.payoutMarginUsd").value(0));
    }

    @Test
    @DisplayName("POST /v1/admin/rates/preview rejects a negative amount with 400")
    void preview_negativeAmount_returns400() throws Exception {
        String body = """
                {"fromCcy":"USD","toCcy":"KRW","amount":"-5.00","direction":"COLLECTION","partnerId":1}
                """;
        mvc.perform(post("/v1/admin/rates/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }
}
