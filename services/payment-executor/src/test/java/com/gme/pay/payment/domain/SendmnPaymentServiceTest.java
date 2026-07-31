package com.gme.pay.payment.domain;

import com.gme.pay.payment.domain.GmeremitPaymentService.WalletResult;
import com.gme.pay.payment.domain.client.PrefundingClient;
import com.gme.pay.payment.domain.client.QrClient;
import com.gme.pay.payment.domain.client.RateClient;
import com.gme.pay.payment.domain.client.RevenueLedgerClient;
import com.gme.pay.payment.domain.client.SchemeClient;
import com.gme.pay.payment.domain.client.TransactionClient;
import com.gme.pay.payment.persistence.ExecutionAttemptRepository;
import com.gme.pay.payment.persistence.RevenuePostingFailureStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SendmnPaymentService}.
 *
 * <p>Tests:
 * <ol>
 *   <li>SENDMN happy path — FX math exact (10000 KRW * 3.5 mid-rate * 0.98 margin = 34300 MNT).
 *   <li>Insufficient prefunding — scheme is NEVER called.
 *   <li>Transaction-mgmt invoked with correct fields (partnerId, direction=OVERSEAS, currency=MNT).
 *   <li>Revenue-ledger invoked with FX margin + fee.
 *   <li>Scheme decline — prefunding is reversed, DECLINED returned.
 * </ol>
 */
class SendmnPaymentServiceTest {

    // ---- mocks ----
    private QrClient qrClient;
    private RateClient rateClient;
    private PrefundingClient prefundingClient;
    private SchemeClient schemeClient;
    private ExecutionAttemptRepository attemptRepository;
    private TransactionClient transactionClient;
    private RevenueLedgerClient revenueLedgerClient;

    // mid rate: 3.5 MNT per KRW
    private static final BigDecimal MID_RATE = new BigDecimal("3.5");
    private static final BigDecimal FX_MARGIN = new BigDecimal("0.02");
    private static final long PARTNER_ID = 2L;
    /** SchemeId.resolve("sendmn") — the platform roster's numeric id for the Mongolia corridor. */
    private static final long SENDMN_SCHEME_ID = 9L;
    /** fxMarginKrw (10000 × 0.02 = 200.00) converted at the 1350 KRW/USD fallback, 4dp HALF_UP. */
    private static final BigDecimal EXPECTED_FX_MARGIN_USD = new BigDecimal("0.1481");

    @BeforeEach
    void setUp() {
        qrClient = mock(QrClient.class);
        rateClient = mock(RateClient.class);
        prefundingClient = mock(PrefundingClient.class);
        schemeClient = mock(SchemeClient.class);
        attemptRepository = mock(ExecutionAttemptRepository.class);
        transactionClient = mock(TransactionClient.class);
        revenueLedgerClient = mock(RevenueLedgerClient.class);

        // Default stubs
        when(qrClient.resolve(anyString())).thenReturn(
                new QrClient.MerchantView("M001", "MNT Merchant", "MNT", "zeropay", "RETAIL", true));

        when(rateClient.fetchLiveRate("KRW", "MNT")).thenReturn(
                new RateClient.LiveRate("KRW", "MNT", MID_RATE, Instant.now(), "sim"));

        when(prefundingClient.deduct(anyLong(), anyString(), any())).thenReturn(
                new PrefundingClient.DeductionResult(
                        new BigDecimal("0.038"), new BigDecimal("100.000")));

        when(schemeClient.submitMpm(any())).thenReturn(
                new SchemeClient.MpmSubmitResponse("ZP_AUTH_001", "ZP_TXN_001", Instant.now()));

        when(transactionClient.createPending(any())).thenReturn(
                new TransactionClient.CreateResult("txn-sendmn-001", "pay-001", Instant.now()));
    }

    private SendmnPaymentService service() {
        return new SendmnPaymentService(
                qrClient, rateClient, prefundingClient, schemeClient,
                attemptRepository, /* lenient */ false, FX_MARGIN,
                transactionClient, revenueLedgerClient);
    }

    // =========================================================================
    // Test 1: SENDMN happy path — FX math exact
    // amountKrw = 10000
    // midRate   = 3.5
    // fxMargin  = 0.02
    // offerRate = 3.5 * (1 - 0.02) = 3.5 * 0.98 = 3.43
    // payAmountMnt = 10000 * 3.43 = 34300 MNT
    // fxMarginKrw  = 10000 * 0.02 = 200 KRW
    // =========================================================================

    @Test
    @DisplayName("SENDMN happy path: FX math 10000 KRW * 3.5 mid * 0.98 = 34300 MNT")
    void sendmn_happyPath_fxMathExact() {
        WalletResult result = service().pay("ZPQR_MNT", new BigDecimal("10000"), "user-mn-001", PARTNER_ID);

        assertTrue(result.approved(), "should be APPROVED");
        assertEquals("ZP_TXN_001", result.schemeTxnRef());
        assertEquals("MNT Merchant", result.merchantName());

        // KRW amounts
        assertEquals(new BigDecimal("10000"), result.payAmountKrw());
        assertEquals(new BigDecimal("500"), result.feeKrw());
        assertEquals(new BigDecimal("10500"), result.chargedKrw());

        // FX fields
        assertTrue(result.fxApplied());
        assertNotNull(result.fxRate());

        // The key assertion: exact MNT payout
        // offerRate = 3.5 * 0.98 = 3.43 MNT/KRW
        // payAmountMnt = 10000 * 3.43 = 34300
        assertEquals(new BigDecimal("34300"), result.payAmountMnt());
    }

    // =========================================================================
    // Test 2: Insufficient prefunding — scheme is NEVER called
    // =========================================================================

    @Test
    @DisplayName("Insufficient prefunding: scheme submitMpm is never called")
    void sendmn_insufficientPrefunding_schemeNeverCalled() {
        when(prefundingClient.deduct(anyLong(), anyString(), any()))
                .thenThrow(new InsufficientPrefundingException(
                        new BigDecimal("0.005"), new BigDecimal("0.038")));

        WalletResult result = service().pay("ZPQR_MNT", new BigDecimal("10000"), "user-mn-002", PARTNER_ID);

        assertFalse(result.approved(), "should be DECLINED");
        assertEquals("INSUFFICIENT_PREFUNDING", result.declineReason());

        // Scheme must NOT have been called
        verify(schemeClient, never()).submitMpm(any());
    }

    // =========================================================================
    // Test 3: Transaction-mgmt invoked with correct field values
    // =========================================================================

    @Test
    @DisplayName("TransactionClient.createPending called with direction=OVERSEAS, currency=MNT")
    void sendmn_transactionMgmt_correctFields() {
        service().pay("ZPQR_MNT", new BigDecimal("10000"), "user-mn-003", PARTNER_ID);

        ArgumentCaptor<TransactionClient.CreateRequest> createCaptor =
                ArgumentCaptor.forClass(TransactionClient.CreateRequest.class);
        verify(transactionClient).createPending(createCaptor.capture());

        TransactionClient.CreateRequest req = createCaptor.getValue();
        assertEquals(PARTNER_ID, req.partnerId());
        assertEquals("OVERSEAS", req.direction());
        assertEquals("MPM", req.paymentMode());
        assertEquals("MNT", req.payoutCurrency());
        // payAmountMnt = 10000 * 3.43 = 34300
        assertEquals(new BigDecimal("34300"), req.targetPayout());
        assertEquals("KRW", req.collectionCurrency());
        assertEquals(new BigDecimal("10000"), req.collectionAmount());
        assertEquals("M001", req.merchantId());
    }

    @Test
    @DisplayName("TransactionClient.commitStatus called with APPROVED, correct prefundDeductedUsd")
    void sendmn_transactionMgmt_commitStatus() {
        service().pay("ZPQR_MNT", new BigDecimal("10000"), "user-mn-004", PARTNER_ID);

        ArgumentCaptor<TransactionClient.StatusPatch> patchCaptor =
                ArgumentCaptor.forClass(TransactionClient.StatusPatch.class);
        verify(transactionClient).commitStatus(eq("txn-sendmn-001"), patchCaptor.capture());

        TransactionClient.StatusPatch patch = patchCaptor.getValue();
        assertEquals(PaymentStatus.APPROVED, patch.newStatus());
        assertEquals("ZP_TXN_001", patch.schemeTxnRef());
        assertEquals("ZP_AUTH_001", patch.schemeApprovalCode());
        // chargedKrw = 10500; chargedUsd = 10500 / 1350 ≈ 0.00777...
        assertNotNull(patch.prefundDeductedUsd(), "prefundDeductedUsd must be set for OVERSEAS");
        assertNotNull(patch.approvedAt());
    }

    // =========================================================================
    // T2-1: the APPROVED commit must carry the REAL margin (it used to pass the
    // 5-arg StatusPatch → null margins → a zero-revenue transaction row).
    // =========================================================================

    @Test
    @DisplayName("T2-1: commitStatus carries the real payout-leg FX margin, not null")
    void sendmn_commitStatus_carriesRealMargin() {
        service().pay("ZPQR_MNT", new BigDecimal("10000"), "user-mn-margin", PARTNER_ID);

        ArgumentCaptor<TransactionClient.StatusPatch> patchCaptor =
                ArgumentCaptor.forClass(TransactionClient.StatusPatch.class);
        verify(transactionClient).commitStatus(eq("txn-sendmn-001"), patchCaptor.capture());

        TransactionClient.StatusPatch patch = patchCaptor.getValue();
        // fxMarginKrw = 10000 * 0.02 = 200.00; krwPerUsd falls back to 1350
        // → fxMarginUsd = 200.00 / 1350 = 0.1481 (4dp HALF_UP)
        assertNotNull(patch.payoutMarginUsd(), "payoutMarginUsd must NOT be null (T2-1)");
        assertEquals(0, patch.payoutMarginUsd().compareTo(EXPECTED_FX_MARGIN_USD),
                "the real KRW→MNT margin must ride the commit");
        // Collection is KRW at the live rate — no collection-leg spread.
        assertEquals(0, patch.collectionMarginUsd().compareTo(BigDecimal.ZERO));
        // collectionUsd = the USD equivalent of chargedKrw actually deducted from the float.
        assertNotNull(patch.collectionUsd(), "collectionUsd must be populated");
        assertEquals(0, patch.collectionUsd().compareTo(patch.prefundDeductedUsd()));
    }

    // =========================================================================
    // Live USD/KRW prefunding conversion (replaces the hardcoded 1350)
    // =========================================================================

    @Test
    @DisplayName("prefunding USD conversion uses the LIVE USD/KRW rate (10500 / 1380)")
    void sendmn_prefunding_usesLiveUsdKrwRate() {
        when(rateClient.fetchLiveRate("USD", "KRW")).thenReturn(
                new RateClient.LiveRate("USD", "KRW", new BigDecimal("1380"), Instant.now(), "sim"));

        service().pay("ZPQR_MNT", new BigDecimal("10000"), "user-mn-fx1", PARTNER_ID);

        ArgumentCaptor<BigDecimal> usdCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(prefundingClient).deduct(eq(PARTNER_ID), anyString(), usdCaptor.capture());
        // chargedKrw = 10500; chargedUsd = 10500 / 1380 = 7.60869565 (8dp HALF_UP) — the live rate, not 1350
        assertEquals(0, usdCaptor.getValue().compareTo(new BigDecimal("7.60869565")),
                "prefunding USD must use the live 1380 rate, not the 1350 fallback");
    }

    @Test
    @DisplayName("prefunding USD conversion falls back to 1350 when the live USD/KRW rate is unavailable")
    void sendmn_prefunding_fallsBackWhenRateUnavailable() {
        when(rateClient.fetchLiveRate("USD", "KRW")).thenThrow(new RuntimeException("rate provider down"));

        service().pay("ZPQR_MNT", new BigDecimal("10000"), "user-mn-fx2", PARTNER_ID);

        ArgumentCaptor<BigDecimal> usdCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(prefundingClient).deduct(eq(PARTNER_ID), anyString(), usdCaptor.capture());
        // chargedUsd = 10500 / 1350 = 7.77777778 (8dp HALF_UP) — graceful fallback
        assertEquals(0, usdCaptor.getValue().compareTo(new BigDecimal("7.77777778")),
                "prefunding USD must fall back to the 1350 constant");
    }

    // =========================================================================
    // Test 4 (T2-1): FX margin + fee are booked as REVENUE, not as a rounding residual.
    //
    // Both amounts used to go through postRoundingResidual → the REVENUE_ROUNDING
    // account ("rounding gain/loss vs partner booking"), which made the corridor's
    // entire P&L indistinguishable from rounding noise and left the FX-margin /
    // service-charge accounts empty. The correct call is the one the orchestrated
    // ZeroPay/GMEREMIT confirm path uses: postRevenueCapture.
    // =========================================================================

    @Test
    @DisplayName("T2-1: FX margin + fee booked via postRevenueCapture (FX margin + service charge)")
    void sendmn_revenueLedger_booksRevenueCapture() {
        service().pay("ZPQR_MNT", new BigDecimal("10000"), "user-mn-005", PARTNER_ID);

        ArgumentCaptor<LocalDate> dateCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(revenueLedgerClient).postRevenueCapture(
                eq("txn-sendmn-001"),
                eq(PARTNER_ID),
                eq(SENDMN_SCHEME_ID),
                dateCaptor.capture(),
                eq(BigDecimal.ZERO),          // collectionMarginUsd — KRW collected at the live rate
                eq(EXPECTED_FX_MARGIN_USD),   // payoutMarginUsd — 200.00 KRW / 1350 = 0.1481 USD
                eq(new BigDecimal("500")),    // serviceCharge — the ₩500 fee
                eq("KRW"),                    // serviceChargeCcy
                eq(BigDecimal.ZERO));         // feeSharePct — SENDMN has no scheme fee share

        assertNotNull(dateCaptor.getValue(), "revenueDate must be set (KST business date)");
    }

    @Test
    @DisplayName("T2-1: REVENUE_ROUNDING is no longer touched by the SENDMN path")
    void sendmn_revenueLedger_neverPostsRoundingResidual() {
        service().pay("ZPQR_MNT", new BigDecimal("10000"), "user-mn-005b", PARTNER_ID);

        verify(revenueLedgerClient, never()).postRoundingResidual(anyString(), any(), anyString());
    }

    // =========================================================================
    // T2-1 durability: a revenue posting that cannot be delivered is PERSISTED for
    // replay instead of being lost to a log line — and never fails the payment.
    // =========================================================================

    @Test
    @DisplayName("T2-1: a failed revenue posting is persisted for replay, payment still APPROVED")
    void sendmn_failedRevenuePosting_persistedForReplay() {
        RevenuePostingFailureStore failureStore = mock(RevenuePostingFailureStore.class);
        doThrow(new RuntimeException("revenue-ledger down"))
                .when(revenueLedgerClient).postRevenueCapture(
                        anyString(), anyLong(), anyLong(), any(),
                        any(), any(), any(), anyString(), any());

        WalletResult result = new SendmnPaymentService(
                qrClient, rateClient, prefundingClient, schemeClient, attemptRepository,
                /* lenient */ false, FX_MARGIN, transactionClient, revenueLedgerClient, failureStore)
                .pay("ZPQR_MNT", new BigDecimal("10000"), "user-mn-durable", PARTNER_ID);

        // The money already moved — a ledger outage must not fail the payment.
        assertTrue(result.approved(), "a revenue-ledger outage must not fail the payment");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<String> errorCaptor = ArgumentCaptor.forClass(String.class);
        verify(failureStore).record(
                eq("txn-sendmn-001"),
                eq(RevenuePostingFailureStore.TYPE_REVENUE_CAPTURE),
                payloadCaptor.capture(),
                errorCaptor.capture());

        // The persisted payload must be replayable on its own: the exact capture request.
        Map<String, Object> payload = payloadCaptor.getValue();
        assertEquals("txn-sendmn-001", payload.get("txnRef"));
        assertEquals(PARTNER_ID, payload.get("partnerId"));
        assertEquals(SENDMN_SCHEME_ID, payload.get("schemeId"));
        assertEquals(0, ((BigDecimal) payload.get("payoutMarginUsd")).compareTo(EXPECTED_FX_MARGIN_USD));
        assertEquals(0, ((BigDecimal) payload.get("serviceChargeAmount")).compareTo(new BigDecimal("500")));
        assertEquals("KRW", payload.get("serviceChargeCcy"));
        assertTrue(errorCaptor.getValue().contains("revenue-ledger down"),
                "the failure cause must be recorded for the operator");
    }

    @Test
    @DisplayName("T2-1: with no failure store wired the payment still succeeds (degrades to logging)")
    void sendmn_failedRevenuePosting_noStore_stillApproved() {
        doThrow(new RuntimeException("revenue-ledger down"))
                .when(revenueLedgerClient).postRevenueCapture(
                        anyString(), anyLong(), anyLong(), any(),
                        any(), any(), any(), anyString(), any());

        WalletResult result = service()
                .pay("ZPQR_MNT", new BigDecimal("10000"), "user-mn-nostore", PARTNER_ID);

        assertTrue(result.approved());
    }

    // =========================================================================
    // Phase-2 hub wiring: the submit rides the router to the SENDMN adapter
    // =========================================================================

    @Test
    @DisplayName("scheme submit carries schemeId=sendmn, the real MNT amount and the raw qrPayload")
    void sendmn_schemeSubmit_routesToSendmnWithMntAmount() {
        service().pay("ZPQR_MNT", new BigDecimal("10000"), "user-mn-route", PARTNER_ID);

        ArgumentCaptor<SchemeClient.MpmSubmitRequest> captor =
                ArgumentCaptor.forClass(SchemeClient.MpmSubmitRequest.class);
        verify(schemeClient).submitMpm(captor.capture());

        SchemeClient.MpmSubmitRequest req = captor.getValue();
        // Router key: "sendmn" upper-cases to SENDMN → SendmnRestSchemeClient (not ZeroPay).
        assertEquals("sendmn", req.schemeId());
        // The SendMN adapter is MNT-denominated: real MNT payout, not the KRW charge.
        assertEquals("MNT", req.payoutCurrency());
        assertEquals(new BigDecimal("34300"), req.payoutAmount());
        // Raw scanned QR rides through for the adapter's VerifyQr step.
        assertEquals("ZPQR_MNT", req.qrPayload());
    }

    // =========================================================================
    // ADR-016: in-body UNKNOWN / PENDING outcomes (adapter never auto-fails)
    // =========================================================================

    @Test
    @DisplayName("UNKNOWN submit outcome resolved APPROVED by lookupStatus → payment approved")
    void sendmn_unknownOutcome_lookupApproved() {
        when(schemeClient.submitMpm(any())).thenReturn(
                new SchemeClient.MpmSubmitResponse("UNKNOWN", "SMN-TOKEN-1", Instant.now()));
        when(schemeClient.lookupStatus(eq("sendmn"), anyString()))
                .thenReturn(SchemeClient.LookupStatus.APPROVED);

        WalletResult result = service().pay("ZPQR_MNT", new BigDecimal("10000"), "user-mn-u1", PARTNER_ID);

        assertTrue(result.approved(), "lookup-confirmed APPROVED must complete the payment");
        verify(prefundingClient, never()).reverse(anyLong(), anyString());
    }

    @Test
    @DisplayName("UNKNOWN submit outcome resolved REJECTED by lookupStatus → prefund reversed, declined")
    void sendmn_unknownOutcome_lookupRejected() {
        when(schemeClient.submitMpm(any())).thenReturn(
                new SchemeClient.MpmSubmitResponse("UNKNOWN", "SMN-TOKEN-2", Instant.now()));
        when(schemeClient.lookupStatus(eq("sendmn"), anyString()))
                .thenReturn(SchemeClient.LookupStatus.REJECTED);

        WalletResult result = service().pay("ZPQR_MNT", new BigDecimal("10000"), "user-mn-u2", PARTNER_ID);

        assertFalse(result.approved());
        assertEquals("SENDMN_REJECTED", result.declineReason());
        verify(prefundingClient).reverse(eq(PARTNER_ID), anyString());
    }

    @Test
    @DisplayName("UNKNOWN submit outcome still unresolved (PENDING probe) → PENDING, prefund KEPT")
    void sendmn_unknownOutcome_staysPending_prefundKept() {
        when(schemeClient.submitMpm(any())).thenReturn(
                new SchemeClient.MpmSubmitResponse("UNKNOWN", "SMN-TOKEN-3", Instant.now()));
        when(schemeClient.lookupStatus(eq("sendmn"), anyString()))
                .thenReturn(SchemeClient.LookupStatus.PENDING);

        WalletResult result = service().pay("ZPQR_MNT", new BigDecimal("10000"), "user-mn-u3", PARTNER_ID);

        assertFalse(result.approved());
        assertEquals("PENDING", result.declineReason());
        // The Confirm may have landed — the prefund must NOT be reversed (anti-double-charge).
        verify(prefundingClient, never()).reverse(anyLong(), anyString());
        verify(transactionClient, never()).createPending(any());
    }

    @Test
    @DisplayName("PENDING submit outcome → PENDING surfaced, prefund KEPT, no lookup needed")
    void sendmn_pendingOutcome_prefundKept() {
        when(schemeClient.submitMpm(any())).thenReturn(
                new SchemeClient.MpmSubmitResponse("PENDING", "SMN-TOKEN-4", Instant.now()));

        WalletResult result = service().pay("ZPQR_MNT", new BigDecimal("10000"), "user-mn-p1", PARTNER_ID);

        assertFalse(result.approved());
        assertEquals("PENDING", result.declineReason());
        verify(prefundingClient, never()).reverse(anyLong(), anyString());
    }

    // =========================================================================
    // Test 5: Scheme decline → prefunding reversed, DECLINED returned
    // =========================================================================

    @Test
    @DisplayName("Scheme decline: prefunding is reversed and DECLINED returned")
    void sendmn_schemeDecline_prefundingReversed() {
        when(schemeClient.submitMpm(any()))
                .thenThrow(new SchemeDeclinedException("ZP_ERR_001", "Declined by scheme"));

        WalletResult result = service().pay("ZPQR_MNT", new BigDecimal("10000"), "user-mn-006", PARTNER_ID);

        assertFalse(result.approved());
        assertEquals("ZP_ERR_001", result.declineReason());

        // Prefunding must have been deducted then reversed
        verify(prefundingClient).deduct(eq(PARTNER_ID), anyString(), any());
        verify(prefundingClient).reverse(eq(PARTNER_ID), anyString());

        // No transaction-mgmt calls on decline
        verify(transactionClient, never()).createPending(any());
    }

    // =========================================================================
    // Test 6: Transaction-mgmt failure is resilient — payment still succeeds
    // =========================================================================

    @Test
    @DisplayName("Transaction-mgmt unavailability does not fail the payment")
    void sendmn_transactionMgmtFailure_paymentStillApproved() {
        when(transactionClient.createPending(any()))
                .thenThrow(new RuntimeException("connection refused"));

        WalletResult result = service().pay("ZPQR_MNT", new BigDecimal("10000"), "user-mn-007", PARTNER_ID);

        // Payment must still be APPROVED
        assertTrue(result.approved());
        assertEquals("ZP_TXN_001", result.schemeTxnRef());
    }

    // =========================================================================
    // Test 7: Domestic GMEREMIT path still works (regression guard)
    // =========================================================================

    @Test
    @DisplayName("Domestic GMEREMIT pay() still returns fxApplied=null")
    void domestic_walletResult_noFxFields() {
        // Build a minimal domestic service (no txn/revenue clients)
        QrClient domesticQr = mock(QrClient.class);
        when(domesticQr.resolve(anyString())).thenReturn(
                new QrClient.MerchantView("M002", "Coffee Shop", "KRW", "zeropay", "RETAIL", true));

        SchemeClient domesticScheme = mock(SchemeClient.class);
        when(domesticScheme.submitMpm(any())).thenReturn(
                new SchemeClient.MpmSubmitResponse("ZP_AUTH_D", "ZP_TXN_D", Instant.now()));

        ExecutionAttemptRepository repo = mock(ExecutionAttemptRepository.class);
        GmeremitPaymentService domestic = new GmeremitPaymentService(
                domesticQr, domesticScheme, repo, /* lenient */ false);

        WalletResult result = domestic.pay("ZPQR_DOM", new BigDecimal("50000"), "user-kr-001");

        assertTrue(result.approved());
        assertNull(result.fxApplied(), "domestic must not set fxApplied");
        assertNull(result.fxRate(),    "domestic must not set fxRate");
        assertNull(result.payAmountMnt(), "domestic must not set payAmountMnt");
    }
}
