package com.gme.pay.payment.domain;

import com.gme.pay.payment.domain.GmeremitPaymentService.WalletResult;
import com.gme.pay.payment.domain.client.SchemeClient;
import com.gme.pay.payment.domain.client.SchemeClient.LookupStatus;
import com.gme.pay.payment.domain.client.SchemeClient.MpmSubmitRequest;
import com.gme.pay.payment.domain.client.SchemeClient.MpmSubmitResponse;
import com.gme.pay.payment.domain.client.SmartRouterClient;
import com.gme.pay.payment.domain.client.SmartRouterClient.PartnerSchemeView;
import com.gme.pay.payment.persistence.ExecutionAttemptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link FailoverPaymentRouter} — ADR-016 §3–4 failover + anti-double-charge.
 *
 * <p>Uses a Mockito {@link SchemeClient} and {@link SmartRouterClient} — no running services. The
 * smart-router is stubbed to return the candidate list directly, so failover behaviour is exercised
 * independent of the QR classifier.
 */
class FailoverPaymentRouterTest {

    private static final String QR = "00020101021126150011fonepay.com5802NP5910KINAUN PVT6304ABCD";
    private static final BigDecimal AMT = new BigDecimal("1000");

    private SchemeClient schemeClient;
    private SmartRouterClient smartRouter;
    private ExecutionAttemptRepository attemptRepo;
    private FailoverPaymentRouter router;

    /**
     * Generic (non-Nepal) primary candidate for the failover mechanics. It was NEPAL until T4-1 gave
     * the Nepal corridor its own money path — a Nepal candidate is now DELEGATED, not walked, so it
     * can no longer stand in for "some cross-border scheme" here. The Nepal behaviour has its own
     * tests below.
     */
    private final PartnerSchemeView primary = new PartnerSchemeView(1L, "PrimaryPartner", "khqr", 0);
    private final PartnerSchemeView secondary = new PartnerSchemeView(2L, "SecondaryPartner", "zeropay", 1);
    /** The Nepal corridor candidate — delegated to {@link NepalPaymentService} (T4-1). */
    private final PartnerSchemeView nepal = new PartnerSchemeView(3L, "NepalPartner", "NEPAL", 0);

    @BeforeEach
    void setUp() {
        schemeClient = mock(SchemeClient.class);
        smartRouter = mock(SmartRouterClient.class);
        attemptRepo = mock(ExecutionAttemptRepository.class);
        router = new FailoverPaymentRouter(smartRouter, schemeClient, attemptRepo);
        // NOTE: the classifier will parse QR to fonepay.com; the stub ignores the args and returns
        // whatever the test sets, so classification is not under test here.
    }

    // classifyQr: GMEPay+ is authoritative — a Fonepay QR resolves to Nepal / NPR.
    @Test
    @DisplayName("classifyQr: Fonepay QR → supported, network fonepay.com, country NP, currency NPR")
    void classifyQr_fonepayResolvesToNepalNpr() {
        when(smartRouter.resolve(anyString(), any(), anyString(), anyString()))
                .thenReturn(List.of(nepal));

        FailoverPaymentRouter.QrClassification c = router.classifyQr(QR, "OVERSEAS");

        assertTrue(c.supported());
        assertEquals("fonepay.com", c.network());
        assertEquals("NP", c.country());
        assertEquals("NPR", c.currency());
        assertEquals("NEPAL", c.scheme());
    }

    // classifyQr: a recognised network with no live route → unsupported, no chargeable currency.
    @Test
    @DisplayName("classifyQr: no routing candidates → unsupported, currency null")
    void classifyQr_noRoute_unsupported() {
        when(smartRouter.resolve(anyString(), any(), anyString(), anyString()))
                .thenReturn(List.of());

        FailoverPaymentRouter.QrClassification c = router.classifyQr(QR, "OVERSEAS");

        assertFalse(c.supported());
        assertEquals("NP", c.country());   // corridor still reported
        assertEquals(null, c.currency());  // but nothing chargeable
    }

    // (a) primary technical-fail + lookup NOT_FOUND → secondary APPROVED
    @Test
    @DisplayName("(a) primary technical failure, lookup NOT_FOUND → fails over, secondary APPROVED")
    void primaryTechnicalFail_failsOverToSecondaryApproved() {
        when(smartRouter.resolve(anyString(), any(), anyString(), anyString()))
                .thenReturn(List.of(primary, secondary));

        // primary submit → technical failure (timeout)
        when(schemeClient.submitMpm(any(MpmSubmitRequest.class)))
                .thenThrow(new SchemeTimeoutException("khqr"))
                .thenReturn(new MpmSubmitResponse("ZP_OK", "ZP-TXN-2", Instant.now()));
        // anti-double-charge guard on primary → NOT_FOUND (no charge landed → safe to fail over)
        when(schemeClient.lookupStatus(eq("khqr"), anyString())).thenReturn(LookupStatus.NOT_FOUND);

        WalletResult result = router.pay(QR, AMT, "user-1", "OVERSEAS");

        assertTrue(result.approved());
        assertEquals("ZP-TXN-2", result.schemeTxnRef());
        // two submit attempts: primary (failed) + secondary (approved)
        verify(schemeClient, times(2)).submitMpm(any(MpmSubmitRequest.class));
        verify(schemeClient).lookupStatus(eq("khqr"), anyString());
    }

    // (b) primary business-decline → terminal, secondary NOT tried
    @Test
    @DisplayName("(b) primary business decline (receiver_not_found) → TERMINAL, secondary not tried")
    void primaryBusinessDecline_terminal_noFailover() {
        when(smartRouter.resolve(anyString(), any(), anyString(), anyString()))
                .thenReturn(List.of(primary, secondary));

        when(schemeClient.submitMpm(any(MpmSubmitRequest.class)))
                .thenThrow(new SchemeDeclinedException("receiver_not_found", "no such receiver"));

        WalletResult result = router.pay(QR, AMT, "user-1", "OVERSEAS");

        assertFalse(result.approved());
        assertEquals("receiver_not_found", result.declineReason());
        // exactly ONE submit — no failover on a business decline
        verify(schemeClient, times(1)).submitMpm(any(MpmSubmitRequest.class));
        // and NO lookup (business decline is authoritative, guard not consulted)
        verify(schemeClient, never()).lookupStatus(anyString(), anyString());
    }

    // (c) primary timeout + lookup APPROVED → returns primary, no second submit (NO DOUBLE-CHARGE)
    @Test
    @DisplayName("(c) primary timeout, lookup APPROVED → returns primary, NO second submit (anti-double-charge)")
    void primaryTimeout_lookupApproved_noDoubleCharge() {
        when(smartRouter.resolve(anyString(), any(), anyString(), anyString()))
                .thenReturn(List.of(primary, secondary));

        // primary submit throws (timeout) — outcome unknown
        when(schemeClient.submitMpm(any(MpmSubmitRequest.class)))
                .thenThrow(new SchemeTimeoutException("khqr"));
        // guard: the payment DID land at the primary
        when(schemeClient.lookupStatus(eq("khqr"), anyString())).thenReturn(LookupStatus.APPROVED);

        WalletResult result = router.pay(QR, AMT, "user-1", "OVERSEAS");

        assertTrue(result.approved());
        // CRITICAL: only ONE submit ever happened — the secondary was NOT charged.
        verify(schemeClient, times(1)).submitMpm(any(MpmSubmitRequest.class));
        verify(schemeClient).lookupStatus(eq("khqr"), anyString());
    }

    // (d) single-candidate ZeroPay QR → unchanged APPROVED
    @Test
    @DisplayName("(d) single ZeroPay candidate → one submit, APPROVED (unchanged direct-dispatch behaviour)")
    void singleCandidate_approved() {
        PartnerSchemeView onlyZeroPay = new PartnerSchemeView(0L, "GMEREMIT/ZeroPay", "zeropay", 0);
        when(smartRouter.resolve(anyString(), any(), anyString(), anyString()))
                .thenReturn(List.of(onlyZeroPay));

        when(schemeClient.submitMpm(any(MpmSubmitRequest.class)))
                .thenReturn(new MpmSubmitResponse("ZP_OK", "ZP-TXN-1", Instant.now()));

        WalletResult result = router.pay(QR, AMT, "user-1", "DOMESTIC");

        assertTrue(result.approved());
        assertEquals("ZP-TXN-1", result.schemeTxnRef());
        verify(schemeClient, times(1)).submitMpm(any(MpmSubmitRequest.class));
        verify(schemeClient, never()).lookupStatus(anyString(), anyString());
    }

    @Test
    @DisplayName("no candidates → declined unsupported_qr")
    void noCandidates_declined() {
        when(smartRouter.resolve(anyString(), any(), anyString(), anyString()))
                .thenReturn(List.of());

        WalletResult result = router.pay(QR, AMT, "user-1", "OVERSEAS");

        assertFalse(result.approved());
        assertEquals("unsupported_qr", result.declineReason());
        verify(schemeClient, never()).submitMpm(any(MpmSubmitRequest.class));
    }

    @Test
    @DisplayName("both candidates technical-fail + lookup NOT_FOUND → SCHEME_UNAVAILABLE")
    void allExhausted_schemeUnavailable() {
        when(smartRouter.resolve(anyString(), any(), anyString(), anyString()))
                .thenReturn(List.of(primary, secondary));
        when(schemeClient.submitMpm(any(MpmSubmitRequest.class)))
                .thenThrow(new SchemeTimeoutException("x"));
        when(schemeClient.lookupStatus(anyString(), anyString())).thenReturn(LookupStatus.NOT_FOUND);

        WalletResult result = router.pay(QR, AMT, "user-1", "OVERSEAS");

        assertFalse(result.approved());
        verify(schemeClient, times(2)).submitMpm(any(MpmSubmitRequest.class));
    }

    @Test
    @DisplayName("submit carries the candidate's schemeId (routing key)")
    void submitCarriesCandidateSchemeId() {
        when(smartRouter.resolve(anyString(), any(), anyString(), anyString()))
                .thenReturn(List.of(primary));
        when(schemeClient.submitMpm(any(MpmSubmitRequest.class)))
                .thenReturn(new MpmSubmitResponse("APPROVED", "NP-1", Instant.now()));

        router.pay(QR, AMT, "user-1", "OVERSEAS");

        ArgumentCaptor<MpmSubmitRequest> captor = ArgumentCaptor.forClass(MpmSubmitRequest.class);
        verify(schemeClient).submitMpm(captor.capture());
        assertEquals("khqr", captor.getValue().schemeId());
    }

    // =======================================================================================
    // T4-1 — the Nepal corridor is delegated, never walked
    // =======================================================================================

    @Test
    @DisplayName("T4-1: a Nepal candidate is DELEGATED to the corridor money path, not submitted here")
    void nepalCandidateDelegatesToCorridorMoneyPath() {
        when(smartRouter.resolve(anyString(), any(), anyString(), anyString()))
                .thenReturn(List.of(nepal));
        NepalPaymentService nepalService = mock(NepalPaymentService.class);
        WalletResult delegated = WalletResult.approvedFxInCurrency(
                "TXN-1", "NP-IDX-1", null, AMT, BigDecimal.ZERO, AMT,
                "2026-07-28T10:00:00+09:00", new BigDecimal("0.098"),
                new BigDecimal("98.00"), "NPR");
        when(nepalService.pay(anyString(), any(), any(), anyString(), any())).thenReturn(delegated);
        FailoverPaymentRouter nepalRouter =
                new FailoverPaymentRouter(smartRouter, schemeClient, attemptRepo, null, nepalService);

        WalletResult result = nepalRouter.pay(QR, AMT, "user-np", "OVERSEAS", "KRW",
                WalletPartnerRef.GMEREMIT);

        assertTrue(result.approved());
        assertEquals("NPR", result.payCurrency());
        // The generic loop must not have run: the corridor owns FX/fee/prefunding/revenue.
        verify(schemeClient, never()).submitMpm(any(MpmSubmitRequest.class));
        verify(nepalService).pay(eq(QR), eq(AMT), eq("KRW"), eq("user-np"),
                eq(WalletPartnerRef.GMEREMIT));
    }

    @Test
    @DisplayName("T4-1: no Nepal money path wired → REFUSED (no fallback to the KRW-as-NPR pass-through)")
    void nepalCandidateWithoutMoneyPathRefuses() {
        when(smartRouter.resolve(anyString(), any(), anyString(), anyString()))
                .thenReturn(List.of(nepal));

        assertThrows(CorridorPricingUnavailableException.class,
                () -> router.pay(QR, AMT, "user-np", "OVERSEAS"));

        verify(schemeClient, never()).submitMpm(any(MpmSubmitRequest.class));
    }
}
