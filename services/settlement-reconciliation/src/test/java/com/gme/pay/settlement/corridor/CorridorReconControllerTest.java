package com.gme.pay.settlement.corridor;

import com.gme.pay.settlement.persistence.CorridorReconSummaryEntity;
import com.gme.pay.settlement.persistence.CorridorReconSummaryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract for the cross-border recon API: the operator re-run, the finance summary (money as
 * decimal strings, signed variance preserved), and — load-bearing for honesty about scope — a 404 for
 * a corridor that has no reconciler, e.g. 9Pay, whose hub payout orchestration does not exist yet
 * (register item T4-7). Nothing fabricates a 9Pay result.
 */
@WebMvcTest(CorridorReconController.class)
class CorridorReconControllerTest {

    private static final LocalDate DATE = LocalDate.of(2026, 7, 28);

    @Autowired
    private MockMvc mvc;

    @MockBean
    private CorridorThreeWayReconciler reconciler;

    @MockBean
    private CorridorReconSummaryRepository summaryRepository;

    @Test
    @DisplayName("POST recon: runs the tie-out and returns the day's summary with the signed variance")
    void recon_runsAndReturnsSummary() throws Exception {
        when(reconciler.scheme()).thenReturn("SENDMN");
        when(reconciler.reconcile(DATE)).thenReturn(new CorridorReconResult(
                "SENDMN-3WAY-20260728", DATE, "SENDMN", List.of(), summaryEntity()));

        mvc.perform(post("/v1/settlement/corridor/sendmn/recon").param("date", "2026-07-28"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value("SENDMN-3WAY-20260728"))
                .andExpect(jsonPath("$.scheme").value("SENDMN"))
                .andExpect(jsonPath("$.summary.rateBasisVarianceUsd").value("-3.29945556"))
                .andExpect(jsonPath("$.summary.cumulativeVarianceUsd").value("-3.29945556"))
                .andExpect(jsonPath("$.summary.schemeFeedAvailable").value(false));
    }

    @Test
    @DisplayName("POST recon: a corridor with no reconciler (9Pay — no hub orchestration) is a 404")
    void recon_unknownCorridorIs404() throws Exception {
        when(reconciler.scheme()).thenReturn("SENDMN");

        mvc.perform(post("/v1/settlement/corridor/ninepay/recon").param("date", "2026-07-28"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET summary: one day's summary, 404 when the date was never reconciled")
    void summary_singleDay() throws Exception {
        when(summaryRepository.findBySettlementDateAndScheme(DATE, "SENDMN"))
                .thenReturn(Optional.of(summaryEntity()));
        when(summaryRepository.findBySettlementDateAndScheme(DATE.plusDays(1), "SENDMN"))
                .thenReturn(Optional.empty());

        mvc.perform(get("/v1/settlement/corridor/sendmn/summary").param("date", "2026-07-28"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.corridor").value("KRW->MNT"))
                .andExpect(jsonPath("$.usdDeducted").value("74.44444444"))
                .andExpect(jsonPath("$.usdOwedScheme").value("77.74390000"))
                .andExpect(jsonPath("$.fallbackRateBasisCount").value(1));

        mvc.perform(get("/v1/settlement/corridor/sendmn/summary").param("date", "2026-07-29"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET summary: a from/to period returns the series oldest-first")
    void summary_period() throws Exception {
        when(summaryRepository.findBySchemeAndSettlementDateBetweenOrderBySettlementDateAsc(
                anyString(), any(), any())).thenReturn(List.of(summaryEntity()));

        mvc.perform(get("/v1/settlement/corridor/sendmn/summary")
                        .param("from", "2026-07-01").param("to", "2026-07-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].settlementDate").value("2026-07-28"));
    }

    @Test
    @DisplayName("GET summary: neither date nor from/to is a 400, never an unbounded scan")
    void summary_requiresWindow() throws Exception {
        mvc.perform(get("/v1/settlement/corridor/sendmn/summary"))
                .andExpect(status().isBadRequest());
    }

    private static CorridorReconSummaryEntity summaryEntity() {
        CorridorReconSummaryEntity e = new CorridorReconSummaryEntity();
        e.setSettlementDate(DATE);
        e.setScheme("SENDMN");
        e.setCorridor("KRW->MNT");
        e.setBatchId("SENDMN-3WAY-20260728");
        e.setTxnCount(1);
        e.setChargedKrw(new BigDecimal("100500"));
        e.setLocalPaid(new BigDecimal("255000"));
        e.setLocalCurrency("MNT");
        e.setUsdDeducted(new BigDecimal("74.44444444"));
        e.setUsdOwedScheme(new BigDecimal("77.74390000"));
        e.setRateBasisVarianceUsd(new BigDecimal("-3.29945556"));
        e.setCumulativeVarianceUsd(new BigDecimal("-3.29945556"));
        e.setFallbackRateBasisCount(1);
        e.setBreakCount(1);
        e.setBreakValueUsd(new BigDecimal("3.29945556"));
        e.setSchemeFeedAvailable(false);
        e.setGeneratedAt(Instant.parse("2026-07-29T02:30:00Z"));
        return e;
    }
}
