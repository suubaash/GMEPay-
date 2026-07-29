package com.gme.pay.ledger.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gme.pay.ledger.persistence.RevenueJournalReconciliationService;
import com.gme.pay.ledger.persistence.TrialBalanceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * HTTP contract for the two T2-4 finance surfaces: {@code GET /v1/journals/trial-balance} and
 * {@code GET /v1/revenue/journal-reconciliation}. Standalone MockMvc — the computation itself is covered
 * by {@code TrialBalanceServiceTest} / {@code RevenueJournalReconciliationTest}; what matters here is the
 * wire shape, the money-as-string convention, the 400 on a bad range, and that {@code strict=true} turns
 * an imbalance into a 409 instead of a 200 a caller could ignore.
 */
class TrialBalanceAndReconciliationApiTest {

    private static final LocalDate START = LocalDate.of(2026, 7, 1);
    private static final LocalDate END = LocalDate.of(2026, 7, 31);

    private TrialBalanceService trialBalance;
    private RevenueJournalReconciliationService reconciliation;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        trialBalance = mock(TrialBalanceService.class);
        reconciliation = mock(RevenueJournalReconciliationService.class);
        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mvc = standaloneSetup(
                new TrialBalanceController(trialBalance),
                new RevenueJournalReconciliationController(reconciliation))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om))
                .build();
    }

    @Test
    void trialBalance_returnsPerAccountRowsAndTheBalanceProof() throws Exception {
        when(trialBalance.compute(START, END)).thenReturn(balanced());

        mvc.perform(get("/v1/journals/trial-balance")
                        .param("startDate", "2026-07-01").param("endDate", "2026-07-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanced").value(true))
                .andExpect(jsonPath("$.imbalances").isEmpty())
                // Money rides as a decimal STRING per MONEY_CONVENTION.md.
                .andExpect(jsonPath("$.currencies[0].currency").value("USD"))
                .andExpect(jsonPath("$.currencies[0].debitTotal").value("1.50"))
                .andExpect(jsonPath("$.currencies[0].difference").value("0.00"))
                .andExpect(jsonPath("$.rows[0].account").value("REVENUE_FX_MARGIN"))
                .andExpect(jsonPath("$.rows[0].creditTotal").value("1.50"));
    }

    @Test
    void trialBalance_imbalance_is200WithTheFlagBySefault_and409WhenStrict() throws Exception {
        when(trialBalance.compute(START, END)).thenReturn(unbalanced());

        mvc.perform(get("/v1/journals/trial-balance")
                        .param("startDate", "2026-07-01").param("endDate", "2026-07-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanced").value(false))
                .andExpect(jsonPath("$.imbalances[0].difference").value("7.25"));

        mvc.perform(get("/v1/journals/trial-balance")
                        .param("startDate", "2026-07-01").param("endDate", "2026-07-31")
                        .param("strict", "true"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.imbalances[0].currency").value("USD"));
    }

    @Test
    void trialBalance_rejectsAnInvertedRange() throws Exception {
        mvc.perform(get("/v1/journals/trial-balance")
                        .param("startDate", "2026-07-31").param("endDate", "2026-07-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error_code").value("INVALID_DATE_RANGE"));
    }

    @Test
    void reconciliation_exposesMissingJournalsAndUnmappedMoney() throws Exception {
        when(reconciliation.reconcile(START, END)).thenReturn(notClean());

        mvc.perform(get("/v1/revenue/journal-reconciliation")
                        .param("startDate", "2026-07-01").param("endDate", "2026-07-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clean").value(false))
                .andExpect(jsonPath("$.revenueRecords.source").value("revenue_records"))
                .andExpect(jsonPath("$.revenueRecords.notJournalled").value(1))
                .andExpect(jsonPath("$.revenueRecords.notJournalledTxnRefs[0]").value("TXN-ORPHAN"))
                .andExpect(jsonPath("$.tieOuts[0].variance").value("9.00"))
                .andExpect(jsonPath("$.tieOuts[0].tied").value(false))
                .andExpect(jsonPath("$.unmappedComponents[0].component").value("PARTNER_COMMISSION_SHARE"))
                .andExpect(jsonPath("$.unmappedComponents[0].amount").value("378"));

        mvc.perform(get("/v1/revenue/journal-reconciliation")
                        .param("startDate", "2026-07-01").param("endDate", "2026-07-31")
                        .param("strict", "true"))
                .andExpect(status().isConflict());
    }

    @Test
    void reconciliation_rejectsAnInvertedRange() throws Exception {
        mvc.perform(get("/v1/revenue/journal-reconciliation")
                        .param("startDate", "2026-07-31").param("endDate", "2026-07-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error_code").value("INVALID_DATE_RANGE"));
    }

    // ---- fixtures ----

    private static TrialBalanceView balanced() {
        var usd = new TrialBalanceView.CurrencyTotals("USD",
                new BigDecimal("1.50"), new BigDecimal("1.50"), new BigDecimal("0.00"), true, 2);
        var row = new TrialBalanceView.Row("REVENUE_FX_MARGIN", "USD",
                new BigDecimal("0.00"), new BigDecimal("1.50"), new BigDecimal("-1.50"), 1);
        return new TrialBalanceView(START, END, true, List.of(usd), List.of(row), List.of());
    }

    private static TrialBalanceView unbalanced() {
        var usd = new TrialBalanceView.CurrencyTotals("USD",
                new BigDecimal("8.75"), new BigDecimal("1.50"), new BigDecimal("7.25"), false, 3);
        return new TrialBalanceView(START, END, false, List.of(usd), List.of(), List.of(usd));
    }

    private static RevenueJournalReconciliationView notClean() {
        var records = new RevenueJournalReconciliationView.Coverage(
                "revenue_records", 2, 1, 1, 0, List.of("TXN-ORPHAN"), false);
        var splits = new RevenueJournalReconciliationView.Coverage(
                "commission_splits", 0, 0, 0, 0, List.of(), false);
        var tieOut = new RevenueJournalReconciliationView.TieOut(
                "FX_MARGIN", "REVENUE_FX_MARGIN", "USD",
                new BigDecimal("12.50"), new BigDecimal("3.50"), new BigDecimal("9.00"), false);
        var unmapped = new RevenueJournalReconciliationView.UnmappedComponent(
                "PARTNER_COMMISSION_SHARE", "commission_splits.partner_share_krw", "KRW",
                new BigDecimal("378"), 1, "no account code", "finance owner must decide");
        return new RevenueJournalReconciliationView(
                START, END, records, splits, List.of(tieOut), List.of(unmapped), false);
    }
}
