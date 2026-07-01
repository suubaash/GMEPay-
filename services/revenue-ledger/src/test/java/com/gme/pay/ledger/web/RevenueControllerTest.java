package com.gme.pay.ledger.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gme.pay.ledger.domain.ledger.JournalStore;
import com.gme.pay.ledger.revenue.RevenueRecordService;
import com.gme.pay.ledger.revenue.RevenueRecordStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * Standalone MockMvc tests for {@code GET /v1/revenue}: asserts the response is the canonical shared
 * {@link com.gme.pay.contracts.RevenueSummaryView} shape — including {@code totalRoundingUsd} — so
 * ops-partner-bff and the reporting revenue board converge on one type. Broker-free; stores are mocked.
 */
class RevenueControllerTest {

    private static final LocalDate START = LocalDate.of(2026, 6, 1);
    private static final LocalDate END = LocalDate.of(2026, 6, 30);

    private RevenueRecordStore store;
    private JournalStore journalStore;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        store = mock(RevenueRecordStore.class);
        journalStore = mock(JournalStore.class);
        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mvc = standaloneSetup(new RevenueController(new RevenueRecordService(store, journalStore)))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om))
                .build();
    }

    @Test
    void getRevenue_returnsRevenueSummaryViewShape_withRoundingTotal() throws Exception {
        when(store.sumFxMarginUsdByPartnerAndDateRange(7L, START, END)).thenReturn(new BigDecimal("12.50"));
        when(store.sumServiceChargeByPartnerAndDateRange(7L, START, END)).thenReturn(new BigDecimal("3.00"));
        when(store.countByPartnerAndDateRange(7L, START, END)).thenReturn(4L);
        when(store.serviceChargeCcyByPartnerAndDateRange(7L, START, END)).thenReturn("USD");
        when(journalStore.sumRoundingByDateRange(eq(START), eq(END), eq("USD")))
                .thenReturn(new BigDecimal("0.034"));

        mvc.perform(get("/v1/revenue")
                        .param("partnerId", "7")
                        .param("startDate", "2026-06-01")
                        .param("endDate", "2026-06-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partnerId").value(7))
                .andExpect(jsonPath("$.txnCount").value(4))
                // Money fields ride as decimal STRINGS (RevenueSummaryView @JsonFormat(STRING)).
                .andExpect(jsonPath("$.totalFxMarginUsd").value("12.50"))
                .andExpect(jsonPath("$.totalServiceChargeAmount").value("3.00"))
                .andExpect(jsonPath("$.serviceChargeCcy").value("USD"))
                // The additive IR-3 field is present on the canonical shape.
                .andExpect(jsonPath("$.totalRoundingUsd").value("0.034"));
    }

    @Test
    void getRevenue_zeroRounding_stillExposesTotalRoundingUsdField() throws Exception {
        when(store.sumFxMarginUsdByPartnerAndDateRange(anyLong(), eq(START), eq(END))).thenReturn(BigDecimal.ZERO);
        when(store.sumServiceChargeByPartnerAndDateRange(anyLong(), eq(START), eq(END))).thenReturn(BigDecimal.ZERO);
        when(store.countByPartnerAndDateRange(anyLong(), eq(START), eq(END))).thenReturn(0L);
        when(store.serviceChargeCcyByPartnerAndDateRange(anyLong(), eq(START), eq(END))).thenReturn(null);
        when(journalStore.sumRoundingByDateRange(eq(START), eq(END), eq("USD"))).thenReturn(null);

        mvc.perform(get("/v1/revenue")
                        .param("partnerId", "9")
                        .param("startDate", "2026-06-01")
                        .param("endDate", "2026-06-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRoundingUsd").value("0"))
                .andExpect(jsonPath("$.serviceChargeCcy").value("USD"));
    }

    @Test
    void getRevenue_invalidDateRange_returns400() throws Exception {
        mvc.perform(get("/v1/revenue")
                        .param("partnerId", "7")
                        .param("startDate", "2026-06-30")
                        .param("endDate", "2026-06-01"))
                .andExpect(status().isBadRequest());
    }
}
