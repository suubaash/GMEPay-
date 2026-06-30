package com.gme.pay.payment.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gme.pay.payment.domain.client.PartnerConfigClient;
import com.gme.pay.payment.domain.client.PrefundingClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * Standalone MockMvc tests for GET /v1/balance (backlog 5.2-T27): OVERSEAS below/not-below the
 * threshold, the LOCAL-partner 403, and money serialized as a decimal string (not a JSON number).
 */
class BalanceControllerTest {

    private PrefundingClient prefundingClient;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        prefundingClient = mock(PrefundingClient.class);
        PartnerConfigClient partnerConfigClient = mock(PartnerConfigClient.class);
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        BalanceController controller = new BalanceController(prefundingClient, partnerConfigClient);
        mvc = standaloneSetup(controller)
                .setControllerAdvice(new PaymentExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    void overseas_aboveThreshold_returns200NotBelow_moneyAsString() throws Exception {
        when(prefundingClient.balance(anyString())).thenReturn(new PrefundingClient.BalanceSnapshot(
                new BigDecimal("48234.5600"), new BigDecimal("10000.00"), "USD"));

        mvc.perform(get("/v1/balance")
                        .header("X-Partner-Id", "101")
                        .header("X-Partner-Type", "OVERSEAS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partner_id").value(101))
                .andExpect(jsonPath("$.is_below_threshold").value(false))
                // money must be a JSON STRING, not a number
                .andExpect(jsonPath("$.balance_usd").value("48234.5600"));
    }

    @Test
    void overseas_belowThreshold_returns200Below() throws Exception {
        when(prefundingClient.balance(anyString())).thenReturn(new PrefundingClient.BalanceSnapshot(
                new BigDecimal("9500.00"), new BigDecimal("10000.00"), "USD"));

        mvc.perform(get("/v1/balance")
                        .header("X-Partner-Id", "101")
                        .header("X-Partner-Type", "OVERSEAS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.is_below_threshold").value(true));
    }

    @Test
    void local_returns403Forbidden() throws Exception {
        mvc.perform(get("/v1/balance")
                        .header("X-Partner-Id", "1")
                        .header("X-Partner-Type", "LOCAL"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
}
