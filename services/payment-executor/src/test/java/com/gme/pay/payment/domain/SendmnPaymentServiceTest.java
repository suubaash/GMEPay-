package com.gme.pay.payment.domain;

import com.gme.pay.payment.domain.GmeremitPaymentService.WalletResult;
import com.gme.pay.payment.domain.client.PrefundingClient;
import com.gme.pay.payment.domain.client.QrClient;
import com.gme.pay.payment.domain.client.RateClient;
import com.gme.pay.payment.domain.client.RevenueLedgerClient;
import com.gme.pay.payment.domain.client.SchemeClient;
import com.gme.pay.payment.domain.client.TransactionClient;
import com.gme.pay.payment.persistence.ExecutionAttemptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    // Test 4: Revenue-ledger invoked for FX margin + fee
    // =========================================================================

    @Test
    @DisplayName("RevenueLedgerClient called for FX margin KRW + service fee KRW")
    void sendmn_revenueLedger_fxMarginAndFee() {
        service().pay("ZPQR_MNT", new BigDecimal("10000"), "user-mn-005", PARTNER_ID);

        ArgumentCaptor<String> refCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<BigDecimal> amountCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<String> currencyCaptor = ArgumentCaptor.forClass(String.class);

        verify(revenueLedgerClient, times(2)).postRoundingResidual(
                refCaptor.capture(), amountCaptor.capture(), currencyCaptor.capture());

        // Both calls must use KRW
        assertTrue(currencyCaptor.getAllValues().stream().allMatch("KRW"::equals),
                "all revenue-ledger posts must use KRW");

        // FX margin: 10000 * 0.02 = 200 KRW
        // Fee: 500 KRW
        // We don't assert the order but both values must be in the amounts
        var amounts = amountCaptor.getAllValues();
        assertTrue(amounts.stream().anyMatch(a -> a.compareTo(new BigDecimal("200.00")) == 0),
                "FX margin of 200 KRW must be posted");
        assertTrue(amounts.stream().anyMatch(a -> a.compareTo(new BigDecimal("500")) == 0),
                "Service fee of 500 KRW must be posted");
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
