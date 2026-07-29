package com.gme.pay.scheme.sendmn.api;

import com.gme.pay.scheme.sendmn.dto.DailySettlementResponse;
import com.gme.pay.scheme.sendmn.settlement.SmnSettlementQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract for the read-only daily settlement query consumed by settlement-reconciliation's
 * SENDMN three-way tie-out: money renders as decimal strings (MONEY_CONVENTION) and the hub
 * reference — the tie-out's join key — is on every row.
 */
@WebMvcTest(SmnSettlementQueryController.class)
@Import(ApiExceptionHandler.class)
class SmnSettlementQueryControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private SmnSettlementQueryService service;

    @Test
    @DisplayName("daily: 200 with rows keyed by hubReference, money as decimal strings")
    void daily_ok() throws Exception {
        when(service.confirmedOn(LocalDate.of(2026, 7, 28))).thenReturn(
                new DailySettlementResponse("2026-07-28", "APPROVED", "MNT", "USD",
                        new BigDecimal("3373.000000"), 1,
                        List.of(new DailySettlementResponse.Row(
                                "SENDMN-ref-1", "SMN-TOK-1", "merchant-guid", "MNT",
                                new BigDecimal("10000.00"), "TICKER-1", new BigDecimal("3373.000000"),
                                "USD", new BigDecimal("2.9647"), "APPROVED", "PN-1", "GME145",
                                Instant.parse("2026-07-28T01:00:00Z"),
                                Instant.parse("2026-07-28T01:00:05Z")))));

        mvc.perform(get("/internal/scheme/sendmn/settlement/daily").param("date", "2026-07-28"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-07-28"))
                .andExpect(jsonPath("$.settlementCurCode").value("USD"))
                .andExpect(jsonPath("$.latestRegisteredRate").value("3373.000000"))
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.rows[0].hubReference").value("SENDMN-ref-1"))
                .andExpect(jsonPath("$.rows[0].localAmount").value("10000.00"))
                .andExpect(jsonPath("$.rows[0].fxUsdBuyRate").value("3373.000000"))
                .andExpect(jsonPath("$.rows[0].settlementAmount").value("2.9647"));
    }

    @Test
    @DisplayName("daily: a missing date parameter is a 400, not a silent all-time query")
    void daily_missingDate() throws Exception {
        mvc.perform(get("/internal/scheme/sendmn/settlement/daily"))
                .andExpect(status().is4xxClientError());
    }
}
