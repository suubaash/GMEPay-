package com.gme.pay.ledger.web;

import com.gme.pay.ledger.domain.ledger.LedgerPostingService;
import com.gme.pay.ledger.domain.model.EntryType;
import com.gme.pay.ledger.domain.model.Journal;
import com.gme.pay.ledger.domain.model.LedgerEntry;
import com.gme.pay.ledger.fees.CommissionSplit;
import com.gme.pay.ledger.fees.CommissionSplitRecordService;
import com.gme.pay.ledger.persistence.CommissionSplitRecordEntity;
import com.gme.pay.ledger.revenue.RevenueCaptureService;
import com.gme.pay.ledger.revenue.RevenueRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-layer contract test for the four revenue-ledger endpoints that {@code payment-executor}'s
 * {@code RestRevenueLedgerClient} (and {@code settlement-reconciliation}'s
 * {@code RestRoundingResidualClient}) post to.
 *
 * <h2>Why a {@code @WebMvcTest} slice and not another standalone MockMvc test</h2>
 * This class exists because of a live defect (GAP {@code T3-12}) that every unit test missed: for
 * ~200 real payments {@code POST /v1/journals/rounding-residual} returned <b>HTTP 406 Not
 * Acceptable</b>, so no rounding-residual journal was ever written and the posting silently
 * diverted to the {@code revenue_posting_failures} replay queue.
 *
 * <p>Root cause was pure content negotiation on the response side: the controller returned the
 * <i>domain</i> {@link Journal}, whose accessors are record-style ({@code journalId()},
 * {@code postedAt()}, {@code entries()}) rather than JavaBean getters. Jackson therefore discovers
 * zero properties, {@code ObjectMapper.canSerialize(Journal.class)} is {@code false} (default
 * {@code FAIL_ON_EMPTY_BEANS}), {@code MappingJackson2HttpMessageConverter.canWrite} returns
 * {@code false}, and Spring MVC — finding no converter able to produce the requested
 * representation — raises {@code HttpMediaTypeNotAcceptableException} → <b>406</b>. Note it is a
 * 406, not a 500: the failure happens during converter selection, before serialization.
 *
 * <p>The pre-existing controller tests could not see this because the two {@code /v1/journals}
 * POST endpoints had no HTTP-level test at all — they were only covered at service level
 * ({@code RoundingResidualTest}, {@code RevenueReversalRoundingResidualTest}), which never goes
 * through a message converter. So the assertions below deliberately go through the real
 * Boot-configured converter chain and assert on the response <em>body</em>, not just the status.
 *
 * <p>The sibling endpoints ({@code /v1/revenue/capture}, {@code /v1/revenue/commission-split},
 * {@code /v1/journals/reversal}) are exercised here too — a content-negotiation defect that hid
 * once will hide again.
 */
@WebMvcTest(controllers = {
        RoundingResidualController.class,
        RevenueCaptureController.class,
        CommissionSplitController.class})
class RevenueLedgerHttpContractTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private LedgerPostingService ledgerPostingService;

    @MockBean
    private RevenueCaptureService revenueCaptureService;

    @MockBean
    private CommissionSplitRecordService commissionSplitRecordService;

    private static Journal journal(String reference, String account, String amount, String ccy) {
        return Journal.rehydrate("JRN-1", Instant.parse("2026-07-28T00:00:00Z"), List.of(
                new LedgerEntry("RECEIVABLE_PARTNER", new BigDecimal(amount), ccy, EntryType.DEBIT, reference),
                new LedgerEntry(account, new BigDecimal(amount), ccy, EntryType.CREDIT, reference)));
    }

    // ------------------------------------------------------------------ rounding residual

    /**
     * The regression guard for the 406. Before the fix this failed with
     * {@code Status expected:<200> but was:<406>}; the JSON assertions additionally pin the wire
     * shape so a future "fix" that returns an empty body cannot pass.
     */
    @Test
    void roundingResidual_returns200AndSerialisableJournalBody() throws Exception {
        when(ledgerPostingService.postRoundingResidual(eq("TXN-00001"), any(), eq("USD")))
                .thenReturn(journal("TXN-00001", "REVENUE_ROUNDING", "0.007", "USD"));

        mvc.perform(post("/v1/journals/rounding-residual")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"reference\":\"TXN-00001\",\"residual\":\"0.007\",\"currency\":\"USD\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.journalId").value("JRN-1"))
                .andExpect(jsonPath("$.postedAt").exists())
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[1].account").value("REVENUE_ROUNDING"))
                .andExpect(jsonPath("$.entries[1].type").value("CREDIT"))
                .andExpect(jsonPath("$.entries[1].amount").value("0.007"))
                .andExpect(jsonPath("$.entries[1].currency").value("USD"))
                .andExpect(jsonPath("$.entries[1].reference").value("TXN-00001"));
    }

    /**
     * The client sends no explicit {@code Accept}. That is the real production request shape
     * ({@code RestRevenueLedgerClient} only sets {@code Content-Type}), and it must not 406 either:
     * absent {@code Accept} means "*&#47;*", which is what made the missing-converter case surface as
     * 406 rather than 415.
     */
    @Test
    void roundingResidual_withoutAcceptHeader_returns200() throws Exception {
        when(ledgerPostingService.postRoundingResidual(anyString(), any(), anyString()))
                .thenReturn(journal("TXN-00002", "REVENUE_ROUNDING", "0.004", "USD"));

        mvc.perform(post("/v1/journals/rounding-residual")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reference\":\"TXN-00002\",\"residual\":\"0.004\",\"currency\":\"USD\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.journalId").value("JRN-1"));
    }

    /** Zero residual stays a 204 no-op — the fix must not turn the documented 204 into a body. */
    @Test
    void roundingResidual_zeroResidual_returns204() throws Exception {
        when(ledgerPostingService.postRoundingResidual(anyString(), any(), anyString())).thenReturn(null);

        mvc.perform(post("/v1/journals/rounding-residual")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reference\":\"TXN-00003\",\"residual\":\"0\",\"currency\":\"USD\"}"))
                .andExpect(status().isNoContent());
    }

    /** The 400 branch already had a serialisable body ({@code Map}); pinned so the fix keeps it. */
    @Test
    void roundingResidual_missingReference_returns400WithErrorBody() throws Exception {
        mvc.perform(post("/v1/journals/rounding-residual")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"residual\":\"0.007\",\"currency\":\"USD\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error_code").value("MISSING_REFERENCE"));
    }

    // ------------------------------------------------------------------ sibling: reversal journal

    /** Same defect class: {@code /v1/journals/reversal} also returned the domain {@link Journal}. */
    @Test
    void reversalJournal_returns200AndSerialisableJournalBody() throws Exception {
        when(ledgerPostingService.postReversalJournal(eq("TXN-00004"), any(), eq("USD")))
                .thenReturn(journal("TXN-00004", "REVENUE_REVERSAL", "125.50", "USD"));

        mvc.perform(post("/v1/journals/reversal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reference\":\"TXN-00004\",\"reversalAmount\":\"125.50\",\"currency\":\"USD\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.journalId").value("JRN-1"))
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[1].account").value("REVENUE_REVERSAL"));
    }

    @Test
    void reversalJournal_zeroAmount_returns204() throws Exception {
        when(ledgerPostingService.postReversalJournal(anyString(), any(), anyString())).thenReturn(null);

        mvc.perform(post("/v1/journals/reversal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reference\":\"TXN-00005\",\"reversalAmount\":\"0\",\"currency\":\"USD\"}"))
                .andExpect(status().isNoContent());
    }

    // ------------------------------------------------------------------ sibling: revenue capture

    @Test
    void revenueCapture_returns201AndSerialisableBody() throws Exception {
        RevenueRecord record = RevenueRecord.of("TXN-00006", 7L, 1L, LocalDate.parse("2026-07-28"),
                new BigDecimal("1.00"), new BigDecimal("0.50"),
                new BigDecimal("500"), "KRW", new BigDecimal("0.70"));
        when(revenueCaptureService.capture(anyString(), anyLong(), anyLong(), any(),
                any(), any(), any(), anyString(), any()))
                .thenReturn(new RevenueCaptureService.Result(record, true));

        mvc.perform(post("/v1/revenue/capture")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"txnRef":"TXN-00006","partnerId":7,"schemeId":1,"revenueDate":"2026-07-28",
                                 "collectionMarginUsd":"1.00","payoutMarginUsd":"0.50",
                                 "serviceChargeAmount":"500","serviceChargeCcy":"KRW","feeSharePct":"0.70"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.txnRef").value("TXN-00006"))
                .andExpect(jsonPath("$.fxMarginUsd").value("1.50"));
    }

    // ------------------------------------------------------------------ sibling: commission split

    @Test
    void commissionSplit_returns201AndSerialisableBody() throws Exception {
        CommissionSplit split = new CommissionSplit(8000, 800, 7200, 2160, 5040, 1512, 3528);
        CommissionSplitRecordEntity entity = CommissionSplitRecordEntity.of(
                "TXN-00007", 7L, 1L, LocalDate.parse("2026-07-28"), 1_000_000L,
                new BigDecimal("0.0080"), new BigDecimal("0.0008"),
                new BigDecimal("0.70"), new BigDecimal("0.30"), split,
                Instant.parse("2026-07-28T00:00:00Z"));
        when(commissionSplitRecordService.recordIfAbsent(anyString(), anyLong(), anyLong(), any(),
                anyLong(), any(), any(), any(), any()))
                .thenReturn(new CommissionSplitRecordService.Result(entity, true));

        mvc.perform(post("/v1/revenue/commission-split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"txnRef":"TXN-00007","partnerId":7,"schemeId":1,"revenueDate":"2026-07-28",
                                 "payoutAmountKrw":1000000,"merchantFeeRate":"0.0080","vanFeeRate":"0.0008",
                                 "gmeSharePct":"0.70","partnerSharePct":"0.30"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.txnRef").value("TXN-00007"))
                .andExpect(jsonPath("$.gmeNetShareKrw").value(3528));
    }
}
