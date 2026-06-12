package com.gme.pay.bff.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gme.pay.bff.client.stub.StubPrefundingClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Slice 5 (5B.1) MockMvc test for the BFF's prefunding pass-throughs on
 * {@link PartnerBalanceController} — {@code GET .../{code}/balance} and
 * {@code GET .../{code}/balance-alerts}. Uses the real
 * {@link StubPrefundingClient} (the wiring the BFF runs when
 * {@code gmepay.prefunding.client} is not {@code rest}), mirroring
 * {@link PartnerSettlementControllerTest}.
 *
 * <p>Pins the MONEY_CONVENTION wire shape: {@code balance} / {@code threshold} /
 * {@code balanceUsd} / {@code thresholdUsd} are decimal STRINGS, and
 * {@code pctOfThreshold} is derived at scale 2 (the tier gauge the Admin UI
 * renders 95/85/70 markers on).
 */
class PartnerBalanceControllerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mvc = standaloneSetup(new PartnerBalanceController(new StubPrefundingClient()))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om))
                .build();
    }

    @Test
    @DisplayName("GET /v1/admin/partners/{code}/balance returns the canonical view (money as strings)")
    void getBalance_returnsCanonicalView() throws Exception {
        mvc.perform(get("/v1/admin/partners/{code}/balance", "partner_test_001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partnerCode").value("partner_test_001"))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.balance").value("10000.00"))
                .andExpect(jsonPath("$.threshold").value("1000.00"))
                .andExpect(jsonPath("$.pctOfThreshold").value("1000.00"));
    }

    @Test
    @DisplayName("a below-threshold partner reports pctOfThreshold < 100")
    void getBalance_belowThreshold() throws Exception {
        mvc.perform(get("/v1/admin/partners/{code}/balance", "partner_test_003"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("JPY"))
                .andExpect(jsonPath("$.pctOfThreshold").value("50.00"));
    }

    @Test
    @DisplayName("GET /v1/admin/partners/{code}/balance-alerts returns the alert feed newest-first")
    void getBalanceAlerts_newestFirst() throws Exception {
        mvc.perform(get("/v1/admin/partners/{code}/balance-alerts", "partner_test_003"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].tier").value("TIER_70"))
                .andExpect(jsonPath("$[0].balanceUsd").value("68000.0000"))
                .andExpect(jsonPath("$[0].thresholdUsd").value("100000.0000"))
                .andExpect(jsonPath("$[0].acknowledged").value(false))
                .andExpect(jsonPath("$[1].tier").value("TIER_85"))
                .andExpect(jsonPath("$[2].tier").value("TIER_95"))
                .andExpect(jsonPath("$[2].acknowledged").value(true));
    }

    @Test
    @DisplayName("a provisioned partner without alerts returns an empty array")
    void getBalanceAlerts_noneRaised() throws Exception {
        mvc.perform(get("/v1/admin/partners/{code}/balance-alerts", "partner_test_001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("unknown partner returns 404 on both endpoints")
    void unknownPartner_404() throws Exception {
        mvc.perform(get("/v1/admin/partners/{code}/balance", "ghost_partner"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/admin/partners/{code}/balance-alerts", "ghost_partner"))
                .andExpect(status().isNotFound());
    }
}
