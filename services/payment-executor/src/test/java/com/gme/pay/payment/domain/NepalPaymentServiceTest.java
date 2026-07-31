package com.gme.pay.payment.domain;

import com.gme.pay.payment.domain.GmeremitPaymentService.WalletResult;
import com.gme.pay.payment.domain.client.PartnerConfigClient;
import com.gme.pay.payment.domain.client.PartnerConfigClient.TxnLimits;
import com.gme.pay.payment.domain.client.PrefundingClient;
import com.gme.pay.payment.domain.client.RateClient;
import com.gme.pay.payment.domain.client.RevenueLedgerClient;
import com.gme.pay.payment.domain.client.SchemeClient;
import com.gme.pay.payment.domain.client.TransactionClient;
import com.gme.pay.payment.persistence.ExecutionAttemptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 * T4-1 — the Nepal corridor money path.
 *
 * <p>What this pins, one assertion per element of the gap:
 * <ul>
 *   <li>KRW→NPR FX is APPLIED — the wire amount is never the KRW figure (the defect).</li>
 *   <li>The configured service fee is charged.</li>
 *   <li>Prefunding is deducted exactly once, and REVERSED on a scheme decline.</li>
 *   <li>Revenue lands in the real FX-margin / service-charge accounts, never as a rounding residual.</li>
 *   <li>Unconfigured pricing ⇒ a clean structured refusal: no float moved, no scheme call.</li>
 *   <li>The T4-2 regulatory gate still fires on this path, on the corridor's real USD figure.</li>
 * </ul>
 *
 * <h2>Fixture arithmetic</h2>
 * mid KRW/NPR = {@code 0.10} (1 KRW buys 0.10 NPR), margin 2% ⇒ offer = {@code 0.098}.
 * USD/KRW = 1350. Fee = ₩500.
 * <pre>
 *   100,000 KRW × 0.098      = 9,800.00 NPR payout
 *   chargedKrw               = 100,000 + 500 = 100,500
 *   chargedUsd               = 100,500 / 1350 = 74.44444444
 *   fxMarginKrw              = 100,000 × 0.02 = 2,000.00
 *   fxMarginUsd              = 2,000 / 1350   = 1.4815
 * </pre>
 */
class NepalPaymentServiceTest {

    /** A Fonepay MPM QR (classifies to fonepay.com / NP). */
    private static final String FONEPAY_QR =
            "00020101021126150011fonepay.com5802NP5910KINAUN PVT6304ABCD";

    private static final BigDecimal AMOUNT_KRW = new BigDecimal("100000");
    private static final BigDecimal MID_KRW_NPR = new BigDecimal("0.10");
    private static final BigDecimal KRW_PER_USD = new BigDecimal("1350");
    private static final String MARGIN = "0.02";
    private static final String FEE_KRW = "500";

    /** Expected payout: 100,000 × (0.10 × 0.98) = 9,800.00 NPR. */
    private static final BigDecimal EXPECTED_NPR = new BigDecimal("9800.00");
    /** Expected float debit: (100,000 + 500) / 1350, at the corridor's 8dp USD scale. */
    private static final BigDecimal EXPECTED_CHARGED_USD = new BigDecimal("74.44444444");

    private static final WalletPartnerRef PARTNER = WalletPartnerRef.of("GMEREMIT", 1L);

    private RateClient rateClient;
    private PartnerConfigClient partnerConfigClient;
    private PrefundingClient prefundingClient;
    private SchemeClient schemeClient;
    private TransactionClient transactionClient;
    private RevenueLedgerClient revenueLedgerClient;
    private ExecutionAttemptRepository attemptRepository;

    @BeforeEach
    void setUp() {
        rateClient = mock(RateClient.class);
        partnerConfigClient = mock(PartnerConfigClient.class);
        prefundingClient = mock(PrefundingClient.class);
        schemeClient = mock(SchemeClient.class);
        transactionClient = mock(TransactionClient.class);
        revenueLedgerClient = mock(RevenueLedgerClient.class);
        attemptRepository = mock(ExecutionAttemptRepository.class);

        when(rateClient.fetchLiveRate("KRW", "NPR")).thenReturn(
                new RateClient.LiveRate("KRW", "NPR", MID_KRW_NPR, Instant.now(), "sim"));
        when(rateClient.fetchLiveRate("USD", "KRW")).thenReturn(
                new RateClient.LiveRate("USD", "KRW", KRW_PER_USD, Instant.now(), "sim"));
        when(prefundingClient.deduct(anyLong(), anyString(), any())).thenReturn(
                new PrefundingClient.DeductionResult(EXPECTED_CHARGED_USD, new BigDecimal("1000")));
        when(prefundingClient.reverse(anyLong(), anyString())).thenReturn(
                new PrefundingClient.ReverseResult(EXPECTED_CHARGED_USD, new BigDecimal("1000")));
        when(schemeClient.submitMpm(any())).thenReturn(
                new SchemeClient.MpmSubmitResponse("APPROVED", "NP-IDX-1", Instant.now()));
        when(transactionClient.createPending(any())).thenReturn(
                new TransactionClient.CreateResult("TXN-NP-1", "PAY-1", Instant.now()));
        // No partner limits by default — the limit tests opt in.
        when(partnerConfigClient.resolveLimits(anyString())).thenReturn(Optional.empty());
    }

    // ---- wiring helpers -------------------------------------------------------------------

    /** Pricing with the terms supplied as module config (an owner-entered value, not a default). */
    private NepalCorridorPricing configuredPricing() {
        return new NepalCorridorPricing(rateClient, partnerConfigClient, MARGIN, FEE_KRW);
    }

    /** Pricing with NO terms anywhere — the unconfigured corridor. */
    private NepalCorridorPricing unpricedPricing() {
        when(partnerConfigClient.resolveFxConfig(anyString())).thenReturn(Optional.empty());
        when(partnerConfigClient.resolveServiceFeeUsd(anyString(), anyString(), anyString(), any()))
                .thenReturn(Optional.empty());
        return new NepalCorridorPricing(rateClient, partnerConfigClient);
    }

    private NepalPaymentService service(NepalCorridorPricing pricing) {
        return new NepalPaymentService(schemeClient, attemptRepository, pricing, prefundingClient,
                transactionClient, revenueLedgerClient, null, WalletLimitGate.disabled());
    }

    private NepalPaymentService service() {
        return service(configuredPricing());
    }

    private WalletResult pay(NepalPaymentService service) {
        return service.pay(FONEPAY_QR, AMOUNT_KRW, "KRW", "user-np", PARTNER);
    }

    private SchemeClient.MpmSubmitRequest capturedSubmit() {
        ArgumentCaptor<SchemeClient.MpmSubmitRequest> captor =
                ArgumentCaptor.forClass(SchemeClient.MpmSubmitRequest.class);
        verify(schemeClient).submitMpm(captor.capture());
        return captor.getValue();
    }

    private void assertNothingHappened() {
        verify(schemeClient, never()).submitMpm(any());
        verify(prefundingClient, never()).deduct(anyLong(), anyString(), any());
        verify(revenueLedgerClient, never()).postRevenueCapture(anyString(), anyLong(), anyLong(),
                any(), any(), any(), any(), anyString(), any());
    }

    // =======================================================================================
    // FX — the defect itself
    // =======================================================================================

    @Test
    @DisplayName("FX applied: the wire amount is the FX'd NPR payout, NEVER the KRW pass-through")
    void krwIsConvertedToNpr_neverPassedThrough() {
        WalletResult result = pay(service());

        assertTrue(result.approved());
        SchemeClient.MpmSubmitRequest submit = capturedSubmit();
        assertEquals("NPR", submit.payoutCurrency());
        assertEquals(0, EXPECTED_NPR.compareTo(submit.payoutAmount()),
                "the adapter must receive the FX'd NPR payout, got " + submit.payoutAmount());
        assertNotEquals(0, AMOUNT_KRW.compareTo(submit.payoutAmount()),
                "the KRW amount must NEVER be sent as NPR (the T4-1 defect)");
        assertEquals("NEPAL", submit.schemeId());
    }

    @Test
    @DisplayName("the offer rate carries the configured margin: mid 0.10 − 2% = 0.098")
    void offerRateAppliesConfiguredMargin() {
        WalletResult result = pay(service());

        assertTrue(Boolean.TRUE.equals(result.fxApplied()));
        assertEquals(0, new BigDecimal("0.098").compareTo(result.fxRate()));
        assertEquals("NPR", result.payCurrency());
        assertEquals(0, EXPECTED_NPR.compareTo(result.payAmountMnt()));
    }

    @Test
    @DisplayName("NPR-quoted request: the KRW collection is DERIVED from the payout (still FX, no pass-through)")
    void nprQuotedRequestDerivesTheKrwCollection() {
        WalletResult result = service().pay(FONEPAY_QR, EXPECTED_NPR, "NPR", "user-np", PARTNER);

        assertTrue(result.approved());
        // 9,800 NPR ÷ 0.098 = 100,000 KRW collected, + the ₩500 fee.
        assertEquals(0, AMOUNT_KRW.compareTo(result.payAmountKrw()));
        assertEquals(0, new BigDecimal("100500").compareTo(result.chargedKrw()));
        assertEquals(0, EXPECTED_NPR.compareTo(capturedSubmit().payoutAmount()));
    }

    @Test
    @DisplayName("an amount in neither KRW nor NPR is rejected, not reinterpreted")
    void unsupportedAmountCurrencyRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> service().pay(FONEPAY_QR, AMOUNT_KRW, "USD", "user-np", PARTNER));
        assertNothingHappened();
    }

    // =======================================================================================
    // Fee + prefunding
    // =======================================================================================

    @Test
    @DisplayName("fee applied: chargedKrw = amount + configured fee, and it rides the response")
    void feeIsApplied() {
        WalletResult result = pay(service());

        assertEquals(0, new BigDecimal(FEE_KRW).compareTo(result.feeKrw()));
        assertEquals(0, new BigDecimal("100500").compareTo(result.chargedKrw()));
        assertEquals(0, AMOUNT_KRW.compareTo(result.payAmountKrw()));
    }

    @Test
    @DisplayName("prefunding deducted EXACTLY ONCE, for the fee-inclusive USD charge")
    void prefundingDeductedOnce() {
        pay(service());

        ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
        verify(prefundingClient, times(1)).deduct(eq(1L), anyString(), amount.capture());
        assertEquals(0, EXPECTED_CHARGED_USD.compareTo(amount.getValue()));
        verify(prefundingClient, never()).reverse(anyLong(), anyString());
    }

    @Test
    @DisplayName("scheme decline → prefunding REVERSED on the same reference, declined result")
    void prefundingReversedOnSchemeDecline() {
        when(schemeClient.submitMpm(any()))
                .thenThrow(new SchemeDeclinedException("receiver_not_found", "no such receiver"));

        WalletResult result = pay(service());

        assertFalse(result.approved());
        assertEquals("receiver_not_found", result.declineReason());
        ArgumentCaptor<String> deductRef = ArgumentCaptor.forClass(String.class);
        verify(prefundingClient).deduct(eq(1L), deductRef.capture(), any());
        verify(prefundingClient).reverse(eq(1L), eq(deductRef.getValue()));
    }

    @Test
    @DisplayName("non-APPROVED scheme status → prefunding reversed, nothing booked")
    void prefundingReversedOnNonApprovedStatus() {
        when(schemeClient.submitMpm(any())).thenReturn(
                new SchemeClient.MpmSubmitResponse("FAILED", "NP-IDX-2", Instant.now()));

        WalletResult result = pay(service());

        assertFalse(result.approved());
        verify(prefundingClient).reverse(eq(1L), anyString());
        verify(revenueLedgerClient, never()).postRevenueCapture(anyString(), anyLong(), anyLong(),
                any(), any(), any(), any(), anyString(), any());
    }

    @Test
    @DisplayName("PENDING scheme status → prefund KEPT (the payment may have landed)")
    void prefundingKeptOnPending() {
        when(schemeClient.submitMpm(any())).thenReturn(
                new SchemeClient.MpmSubmitResponse("PENDING", "NP-IDX-3", Instant.now()));

        WalletResult result = pay(service());

        assertFalse(result.approved());
        assertEquals("PENDING", result.declineReason());
        verify(prefundingClient, never()).reverse(anyLong(), anyString());
    }

    @Test
    @DisplayName("insufficient float → declined before the scheme is called")
    void insufficientPrefundingDeclinesWithoutSchemeCall() {
        when(prefundingClient.deduct(anyLong(), anyString(), any()))
                .thenThrow(new InsufficientPrefundingException(BigDecimal.ZERO, EXPECTED_CHARGED_USD));

        WalletResult result = pay(service());

        assertFalse(result.approved());
        assertEquals("INSUFFICIENT_PREFUNDING", result.declineReason());
        verify(schemeClient, never()).submitMpm(any());
    }

    // =======================================================================================
    // Revenue + transaction values
    // =======================================================================================

    @Test
    @DisplayName("revenue booked as FX margin + service charge — NOT as a rounding residual")
    void revenueBookedToRealAccounts() {
        pay(service());

        verify(revenueLedgerClient).postRevenueCapture(
                eq("TXN-NP-1"),
                eq(1L),
                eq(8L),                              // SchemeId.resolve("nepal")
                any(LocalDate.class),
                eq(BigDecimal.ZERO),                 // collectionMarginUsd
                eq(new BigDecimal("1.4815")),        // payoutMarginUsd = 2,000 KRW / 1350
                eq(new BigDecimal("500")),           // serviceCharge
                eq("KRW"),
                eq(BigDecimal.ZERO));                // feeSharePct
        // The T2-1 mistake must not reappear: corridor P&L never lands in REVENUE_ROUNDING.
        verify(revenueLedgerClient, never()).postRoundingResidual(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("the APPROVED commit carries REAL margin/collection values, not nulls")
    void transactionCommitCarriesRealMargins() {
        pay(service());

        ArgumentCaptor<TransactionClient.StatusPatch> patch =
                ArgumentCaptor.forClass(TransactionClient.StatusPatch.class);
        verify(transactionClient).commitStatus(eq("TXN-NP-1"), patch.capture());
        TransactionClient.StatusPatch p = patch.getValue();

        assertEquals(PaymentStatus.APPROVED, p.newStatus());
        assertEquals("NP-IDX-1", p.schemeTxnRef());
        assertNotNull(p.payoutMarginUsd(), "payoutMarginUsd must not be null (was, pre-T4-1)");
        assertEquals(0, new BigDecimal("1.4815").compareTo(p.payoutMarginUsd()));
        assertEquals(0, BigDecimal.ZERO.compareTo(p.collectionMarginUsd()));
        assertEquals(0, EXPECTED_CHARGED_USD.compareTo(p.collectionUsd()));
        assertEquals(0, EXPECTED_CHARGED_USD.compareTo(p.prefundDeductedUsd()));
    }

    @Test
    @DisplayName("the transaction records the KRW collection leg and the NPR payout leg")
    void transactionRecordsBothLegs() {
        pay(service());

        ArgumentCaptor<TransactionClient.CreateRequest> create =
                ArgumentCaptor.forClass(TransactionClient.CreateRequest.class);
        verify(transactionClient).createPending(create.capture());
        TransactionClient.CreateRequest r = create.getValue();

        assertEquals("NPR", r.payoutCurrency());
        assertEquals(0, EXPECTED_NPR.compareTo(r.targetPayout()));
        assertEquals("KRW", r.collectionCurrency());
        assertEquals(0, AMOUNT_KRW.compareTo(r.collectionAmount()));
        assertEquals("OVERSEAS", r.direction());
    }

    // =======================================================================================
    // Fail-closed: unconfigured pricing
    // =======================================================================================

    @Nested
    @DisplayName("unpriceable corridor refuses cleanly")
    class FailClosed {

        @Test
        @DisplayName("no FX margin configured → CORRIDOR_PRICING_NOT_CONFIGURED, nothing moved")
        void noMarginConfigured() {
            CorridorPricingUnavailableException ex = assertThrows(
                    CorridorPricingUnavailableException.class, () -> pay(service(unpricedPricing())));

            assertEquals(CorridorPricingUnavailableException.CODE_NOT_CONFIGURED, ex.code());
            assertFalse(ex.retryable(), "an owner must enter the terms — retrying cannot help");
            assertTrue(ex.getMessage().contains("FX margin"), ex.getMessage());
            assertNothingHappened();
        }

        @Test
        @DisplayName("margin but no fee configured → refused, nothing moved")
        void noFeeConfigured() {
            when(partnerConfigClient.resolveServiceFeeUsd(anyString(), anyString(), anyString(), any()))
                    .thenReturn(Optional.empty());
            NepalCorridorPricing marginOnly =
                    new NepalCorridorPricing(rateClient, partnerConfigClient, MARGIN, "");

            CorridorPricingUnavailableException ex = assertThrows(
                    CorridorPricingUnavailableException.class, () -> pay(service(marginOnly)));

            assertEquals(CorridorPricingUnavailableException.CODE_NOT_CONFIGURED, ex.code());
            assertTrue(ex.getMessage().contains("service fee"), ex.getMessage());
            assertNothingHappened();
        }

        @Test
        @DisplayName("no live KRW/NPR rate → CORRIDOR_RATE_UNAVAILABLE (retryable), never a fallback rate")
        void noLiveRate() {
            when(rateClient.fetchLiveRate("KRW", "NPR"))
                    .thenThrow(new PaymentException("rate provider down"));

            CorridorPricingUnavailableException ex = assertThrows(
                    CorridorPricingUnavailableException.class, () -> pay(service()));

            assertEquals(CorridorPricingUnavailableException.CODE_RATE_UNAVAILABLE, ex.code());
            assertTrue(ex.retryable());
            assertNothingHappened();
        }

        @Test
        @DisplayName("no live USD/KRW rate → refused; the 1350 limit-check fallback is NOT used to price")
        void noLiveUsdKrwRate() {
            when(rateClient.fetchLiveRate("USD", "KRW"))
                    .thenThrow(new PaymentException("rate provider down"));

            CorridorPricingUnavailableException ex = assertThrows(
                    CorridorPricingUnavailableException.class, () -> pay(service()));

            assertEquals(CorridorPricingUnavailableException.CODE_RATE_UNAVAILABLE, ex.code());
            assertNothingHappened();
        }

        @Test
        @DisplayName("a nonsense configured margin is refused, not clamped")
        void invalidMarginRefused() {
            NepalCorridorPricing bad =
                    new NepalCorridorPricing(rateClient, partnerConfigClient, "1.5", FEE_KRW);

            assertThrows(CorridorPricingUnavailableException.class, () -> pay(service(bad)));
            assertNothingHappened();
        }

        @Test
        @DisplayName("no prefunding ledger wired → refused, no scheme call")
        void noPrefundingLedger() {
            NepalPaymentService svc = new NepalPaymentService(schemeClient, attemptRepository,
                    configuredPricing(), null, WalletLimitGate.disabled());

            assertThrows(CorridorPricingUnavailableException.class,
                    () -> svc.pay(FONEPAY_QR, AMOUNT_KRW, "KRW", "user-np", PARTNER));
            verify(schemeClient, never()).submitMpm(any());
        }
    }

    // =======================================================================================
    // Pricing sourced from config-registry's commercial terms
    // =======================================================================================

    @Test
    @DisplayName("terms come from config-registry: fx-config marginBps + fee-schedule serviceFeeUsd")
    void pricingSourcedFromConfigRegistry() {
        // 250 bps = 2.5% margin; 0.5 USD service fee on this volume.
        when(partnerConfigClient.resolveFxConfig("GMEREMIT")).thenReturn(Optional.of(
                new PartnerConfigClient.FxTerms(new BigDecimal("250"), "MID_MARKET")));
        when(partnerConfigClient.resolveServiceFeeUsd("GMEREMIT", "NEPAL", "OVERSEAS",
                new BigDecimal("74.07407407"))).thenReturn(Optional.of(new BigDecimal("0.5")));

        WalletResult result = pay(service(new NepalCorridorPricing(rateClient, partnerConfigClient)));

        assertTrue(result.approved());
        // offer = 0.10 × (1 − 0.025) = 0.0975 → 100,000 × 0.0975 = 9,750.00 NPR
        assertEquals(0, new BigDecimal("9750.00").compareTo(capturedSubmit().payoutAmount()));
        // fee 0.5 USD × 1350 = ₩675
        assertEquals(0, new BigDecimal("675").compareTo(result.feeKrw()));
    }

    // =======================================================================================
    // T4-2 regulatory gating is still enforced on this path
    // =======================================================================================

    @Nested
    @DisplayName("T4-2 limit gating still enforced")
    class LimitGating {

        /** The statutory 소액해외송금업 regime: 5,000 USD per txn / 50,000 USD annual (V020). */
        private TxnLimits soaekHaeoemong() {
            return new TxnLimits(null, new BigDecimal("5000"), null, null,
                    new BigDecimal("50000"), "SOAEK_HAEOEMONG", null);
        }

        private NepalPaymentService gated() {
            return new NepalPaymentService(schemeClient, attemptRepository, configuredPricing(),
                    prefundingClient, transactionClient, revenueLedgerClient, null,
                    new WalletLimitGate(partnerConfigClient, prefundingClient, rateClient));
        }

        @Test
        @DisplayName("per-txn ceiling breached → declined, NO scheme call, NO float moved")
        void perTxnCeilingBreached() {
            when(partnerConfigClient.resolveLimits("GMEREMIT"))
                    .thenReturn(Optional.of(soaekHaeoemong()));

            // 8.1M KRW ≈ 6,000 USD > the 5,000 USD ceiling.
            assertThrows(TransactionLimitExceededException.class,
                    () -> gated().pay(FONEPAY_QR, new BigDecimal("8100000"), "KRW", "u", PARTNER));

            assertNothingHappened();
        }

        @Test
        @DisplayName("the cap is charged on the corridor's REAL fee-inclusive USD figure")
        void capChargedOnRealUsdFigure() {
            when(partnerConfigClient.resolveLimits("GMEREMIT"))
                    .thenReturn(Optional.of(soaekHaeoemong()));

            assertTrue(pay(gated()).approved());

            verify(prefundingClient).chargeCumulative(eq(1L), anyString(),
                    eq(EXPECTED_CHARGED_USD), eq(null), eq(null),
                    eq(new BigDecimal("50000")), eq(null));
        }

        @Test
        @DisplayName("a cumulative breach releases nothing it never charged and never calls the scheme")
        void cumulativeBreach() {
            when(partnerConfigClient.resolveLimits("GMEREMIT"))
                    .thenReturn(Optional.of(new TxnLimits(null, null, new BigDecimal("10"),
                            null, null, "SOAEK_HAEOEMONG", null)));
            org.mockito.Mockito.doThrow(new CumulativeLimitExceededException("daily cap"))
                    .when(prefundingClient).chargeCumulative(anyLong(), anyString(), any(),
                            any(), any(), any(), any());

            assertThrows(CumulativeLimitExceededException.class, () -> pay(gated()));

            assertNothingHappened();
        }

        @Test
        @DisplayName("scheme decline returns the consumed cap as well as the float")
        void declineReleasesTheCap() {
            when(partnerConfigClient.resolveLimits("GMEREMIT"))
                    .thenReturn(Optional.of(soaekHaeoemong()));
            when(schemeClient.submitMpm(any()))
                    .thenThrow(new SchemeDeclinedException("invalid_qr", "bad qr"));

            assertFalse(pay(gated()).approved());

            verify(prefundingClient).reverseCumulative(eq(1L), anyString());
            verify(prefundingClient).reverse(eq(1L), anyString());
        }
    }
}
