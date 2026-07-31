package com.gme.pay.scheme.sendmn.adapter;

import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import com.gme.pay.scheme.sendmn.client.SendmnSchemeApiClient;
import com.gme.pay.scheme.sendmn.client.SendmnSchemeApiClient.ConfirmApiResponse;
import com.gme.pay.scheme.sendmn.client.SendmnSchemeApiClient.ConfirmCommand;
import com.gme.pay.scheme.sendmn.client.SendmnSchemeApiClient.PaymentStatusApiResponse;
import com.gme.pay.scheme.sendmn.client.SendmnSchemeApiClient.VerifyQrApiResponse;
import com.gme.pay.scheme.sendmn.dto.StatusResponse;
import com.gme.pay.scheme.sendmn.dto.SubmitMpmRequest;
import com.gme.pay.scheme.sendmn.dto.SubmitMpmResponse;
import com.gme.pay.scheme.sendmn.dto.VerifyQrRequest;
import com.gme.pay.scheme.sendmn.dto.VerifyQrResponse;
import com.gme.pay.scheme.sendmn.fx.FxRateService;
import com.gme.pay.scheme.sendmn.persistence.SmnFxRateEntity;
import com.gme.pay.scheme.sendmn.persistence.SmnFxRateRepository;
import com.gme.pay.scheme.sendmn.persistence.SmnPaymentEntity;
import com.gme.pay.scheme.sendmn.persistence.SmnPaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * Policy tests for {@link SendmnSchemeAdapter}: TX_TOKEN_NO generation + persistence,
 * settlement-amount computation from the registered rate, and — the heart of the
 * scheme's idempotency contract — 304-duplicate and ambiguous-Confirm resolution via
 * PaymentStatus polling (never blind retry, never auto-fail; ADR-016).
 */
class SendmnSchemeAdapterTest {

    private static final Instant NOW = Instant.parse("2026-07-27T04:15:30Z");
    private static final String TOKEN = "SMN20260727041530ABC234";

    private SendmnSchemeApiClient client;
    private SmnPaymentRepository payments;
    private SmnFxRateRepository fxRepo;
    private SendmnSchemeAdapter adapter;

    @BeforeEach
    void setUp() {
        client = mock(SendmnSchemeApiClient.class);
        payments = mock(SmnPaymentRepository.class);
        fxRepo = mock(SmnFxRateRepository.class);
        // Real FxRateService (the settlement math is under test) over a mocked repository.
        FxRateService fxRates = new FxRateService(fxRepo);
        adapter = new SendmnSchemeAdapter(client, payments, fxRates,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(payments.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ------------------------------------------------------------------ helpers

    private void registeredRate(String rate) {
        when(fxRepo.findFirstByLocalCurCodeAndSettlementCurCodeOrderByNoticeDateDescIdDesc("MNT", "USD"))
                .thenReturn(Optional.of(new SmnFxRateEntity(
                        "TICKER-7", "20260727", new BigDecimal(rate), "MNT", "USD")));
    }

    private SmnPaymentEntity verifiedPayment() {
        SmnPaymentEntity p = new SmnPaymentEntity(TOKEN, "qr-payload", "merchant-guid",
                "UB Store", null);
        when(payments.findByTxTokenNo(TOKEN)).thenReturn(Optional.of(p));
        return p;
    }

    private static PaymentStatusApiResponse statusOf(String paymentStatus) {
        return new PaymentStatusApiResponse("0", "success", null, null, null,
                TOKEN, paymentStatus, "PN-9", "GME-R-9", "merchant-guid", "UB Store");
    }

    // ------------------------------------------------------------------ verify-qr

    @Test
    @DisplayName("verify-qr: generates a unique SMN TX_TOKEN_NO, persists the payment, maps merchant fields")
    void verifyQr_generatesTokenAndPersists() {
        when(client.verifyQr(eq("qr-payload"), anyString())).thenReturn(
                new VerifyQrApiResponse("0", "success", null, "11", "merchant-guid",
                        "UB Store", "Ulaanbaatar", "T-1", "ignored-echo", "25000.00"));

        VerifyQrResponse resp = adapter.verifyQr(new VerifyQrRequest("qr-payload", "ref-hub-1"));

        assertTrue(resp.txTokenNo().startsWith("SMN20260727041530"),
                "token carries the SMN prefix + UTC timestamp: " + resp.txTokenNo());
        assertEquals("merchant-guid", resp.merchantId());
        assertEquals("UB Store", resp.merchantName());
        assertEquals("11", resp.qrType());
        assertEquals("25000.00", resp.localAmountMnt());
        assertEquals("MNT", resp.currency());

        ArgumentCaptor<SmnPaymentEntity> saved = ArgumentCaptor.forClass(SmnPaymentEntity.class);
        verify(payments).save(saved.capture());
        assertEquals(resp.txTokenNo(), saved.getValue().getTxTokenNo());
        assertEquals(SmnPaymentEntity.Status.VERIFIED, saved.getValue().getStatus());
        // The hub reference is committed at verify time — BEFORE any Confirm can be
        // sent — so the ADR-016 by-reference probe survives a hub restart.
        assertEquals("ref-hub-1", saved.getValue().getHubReference());
    }

    @Test
    @DisplayName("verify-qr: SendMN rejection (nonzero RES_CODE) → VALIDATION_ERROR, nothing persisted")
    void verifyQr_rejected() {
        when(client.verifyQr(anyString(), anyString())).thenReturn(
                new VerifyQrApiResponse("203", "QR_CODE is missing!", null, null, null,
                        null, null, null, null, null));

        assertThrows(ApiException.class, () -> adapter.verifyQr(new VerifyQrRequest("bad", null)));
        verify(payments, never()).save(any());
    }

    // ------------------------------------------------------------------ submit-mpm

    @Test
    @DisplayName("submit-mpm: computes SETTLEMENT_AMOUNT from the registered rate; Confirm 0 → APPROVED")
    void submit_happyPath() {
        verifiedPayment();
        registeredRate("3373.000000");
        when(client.confirm(any())).thenReturn(new ConfirmApiResponse(
                "0", "success", null, "PN-1", "GME1453767113", "12345", "UB Store"));

        SubmitMpmResponse resp = adapter.submitMpm(
                new SubmitMpmRequest(TOKEN, "10000.00", null, null));

        assertEquals("APPROVED", resp.status());
        assertEquals("PN-1", resp.paymentNo());
        assertEquals("GME1453767113", resp.paymentReceiptNo());
        // 10000.00 / 3373 = 2.96472... → 2.9647 at scale 4 (SendMN verifies — error 307)
        assertEquals("2.9647", resp.settlementAmountUsd());

        ArgumentCaptor<ConfirmCommand> cmd = ArgumentCaptor.forClass(ConfirmCommand.class);
        verify(client).confirm(cmd.capture());
        assertEquals(new BigDecimal("2.9647"), cmd.getValue().settlementAmount());
        assertEquals("TICKER-7", cmd.getValue().fxTickerNo());
        assertEquals("MNT", cmd.getValue().localCurCode());
        assertEquals("USD", cmd.getValue().settlementCurCode());
        assertEquals("20260727041530", cmd.getValue().paymentDatetimeUtc());
    }

    @Test
    @DisplayName("submit-mpm: Confirm 304 (duplicate token) → poll PaymentStatus, no resubmission")
    void submit_duplicate304_pollsStatus() {
        verifiedPayment();
        registeredRate("3373.000000");
        when(client.confirm(any())).thenReturn(new ConfirmApiResponse(
                "304", "TX_TOKEN_NO is duplicated!", null, null, null, null, null));
        when(client.paymentStatus(TOKEN)).thenReturn(statusOf("Approved"));

        SubmitMpmResponse resp = adapter.submitMpm(new SubmitMpmRequest(TOKEN, "10000.00", null, null));

        assertEquals("APPROVED", resp.status());
        assertEquals("PN-9", resp.paymentNo());
        verify(client, times(1)).confirm(any()); // never re-confirmed
        verify(client).paymentStatus(TOKEN);
    }

    @Test
    @DisplayName("submit-mpm: Confirm timeout/5xx → poll PaymentStatus; Processing → PENDING")
    void submit_ambiguousTimeout_pollsToPending() {
        verifiedPayment();
        registeredRate("3373.000000");
        when(client.confirm(any())).thenThrow(
                new ApiException(ErrorCode.SCHEME_UNAVAILABLE, "sendmn /api/Partner/Confirm HTTP 500"));
        when(client.paymentStatus(TOKEN)).thenReturn(statusOf("Processing"));

        SubmitMpmResponse resp = adapter.submitMpm(new SubmitMpmRequest(TOKEN, "10000.00", null, null));

        assertEquals("PENDING", resp.status());
        verify(client, times(1)).confirm(any());
    }

    @Test
    @DisplayName("submit-mpm: Confirm ambiguous AND poll fails → UNKNOWN, never auto-failed")
    void submit_ambiguousAndPollFails_unknown() {
        verifiedPayment();
        registeredRate("3373.000000");
        when(client.confirm(any())).thenThrow(
                new ApiException(ErrorCode.SCHEME_UNAVAILABLE, "timeout"));
        when(client.paymentStatus(TOKEN)).thenThrow(
                new ApiException(ErrorCode.SCHEME_UNAVAILABLE, "timeout again"));

        SubmitMpmResponse resp = adapter.submitMpm(new SubmitMpmRequest(TOKEN, "10000.00", null, null));

        assertEquals("UNKNOWN", resp.status());
        assertNull(resp.paymentNo());
    }

    @Test
    @DisplayName("submit-mpm: already-APPROVED payment replays the stored result without calling SendMN")
    void submit_idempotentReplay() {
        SmnPaymentEntity p = verifiedPayment();
        p.setStatus(SmnPaymentEntity.Status.APPROVED);
        p.setPaymentNo("PN-OLD");

        SubmitMpmResponse resp = adapter.submitMpm(new SubmitMpmRequest(TOKEN, "10000.00", null, null));

        assertEquals("APPROVED", resp.status());
        assertEquals("PN-OLD", resp.paymentNo());
        verify(client, never()).confirm(any());
        verify(client, never()).paymentStatus(anyString());
    }

    @Test
    @DisplayName("submit-mpm: no SendMN-registered rate → SCHEME_UNAVAILABLE (retryable), no Confirm sent")
    void submit_noRegisteredRate() {
        verifiedPayment();
        when(fxRepo.findFirstByLocalCurCodeAndSettlementCurCodeOrderByNoticeDateDescIdDesc("MNT", "USD"))
                .thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class,
                () -> adapter.submitMpm(new SubmitMpmRequest(TOKEN, "10000.00", null, null)));

        assertEquals(ErrorCode.SCHEME_UNAVAILABLE, ex.errorCode());
        verify(client, never()).confirm(any());
    }

    @Test
    @DisplayName("submit-mpm: Confirm 307 settlement mismatch → VALIDATION_ERROR surfaced loudly")
    void submit_settlementMismatch307() {
        verifiedPayment();
        registeredRate("3373.000000");
        when(client.confirm(any())).thenReturn(new ConfirmApiResponse(
                "307", "SETTLEMENT_AMOUNT does not match!", null, null, null, null, null));

        ApiException ex = assertThrows(ApiException.class,
                () -> adapter.submitMpm(new SubmitMpmRequest(TOKEN, "10000.00", null, null)));

        assertEquals(ErrorCode.VALIDATION_ERROR, ex.errorCode());
        verify(client, never()).paymentStatus(anyString()); // 307 is definitive, no poll
    }

    @Test
    @DisplayName("submit-mpm: unknown txTokenNo → PAYMENT_NOT_FOUND (verify-qr must run first)")
    void submit_unknownToken() {
        when(payments.findByTxTokenNo("SMN-NOPE")).thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class,
                () -> adapter.submitMpm(new SubmitMpmRequest("SMN-NOPE", "10.00", null, null)));

        assertEquals(ErrorCode.PAYMENT_NOT_FOUND, ex.errorCode());
    }

    // ------------------------------------------------------------------ status

    @Test
    @DisplayName("status: maps Decrypted → PENDING and refreshes the stored payment")
    void status_decryptedIsPending() {
        verifiedPayment();
        when(client.paymentStatus(TOKEN)).thenReturn(statusOf("Decrypted"));

        StatusResponse resp = adapter.status(TOKEN);

        assertEquals("PENDING", resp.status());
        assertEquals(TOKEN, resp.txTokenNo());
    }

    @Test
    @DisplayName("status: poll failure → UNKNOWN (no exception), APPROVED is never downgraded")
    void status_pollFailureNeverDowngradesApproved() {
        SmnPaymentEntity p = verifiedPayment();
        p.setStatus(SmnPaymentEntity.Status.APPROVED);
        when(client.paymentStatus(TOKEN)).thenThrow(
                new ApiException(ErrorCode.SCHEME_UNAVAILABLE, "down"));

        StatusResponse resp = adapter.status(TOKEN);

        assertEquals("APPROVED", resp.status(), "flaky poll must not downgrade a definitive APPROVED");
    }

    // ------------------------------------------------------------------ status/by-reference

    @Test
    @DisplayName("status by-reference: resolves the hub reference to its payment and polls fresh")
    void statusByReference_happyPath() {
        SmnPaymentEntity p = new SmnPaymentEntity(TOKEN, "qr-payload", "merchant-guid",
                "UB Store", null);
        p.setHubReference("ref-hub-1");
        when(payments.findByHubReferenceOrderByIdDesc("ref-hub-1"))
                .thenReturn(java.util.List.of(p));
        when(client.paymentStatus(TOKEN)).thenReturn(statusOf("Approved"));

        StatusResponse resp = adapter.statusByReference("ref-hub-1");

        assertEquals("APPROVED", resp.status());
        assertEquals(TOKEN, resp.txTokenNo());
        assertEquals("PN-9", resp.paymentNo());
        verify(client).paymentStatus(TOKEN); // a FRESH poll, not a stale DB read
    }

    @Test
    @DisplayName("status by-reference: unknown reference → PAYMENT_NOT_FOUND (404 — no Confirm possible)")
    void statusByReference_unknownReference() {
        when(payments.findByHubReferenceOrderByIdDesc("ref-nope"))
                .thenReturn(java.util.List.of());

        ApiException ex = assertThrows(ApiException.class,
                () -> adapter.statusByReference("ref-nope"));

        assertEquals(ErrorCode.PAYMENT_NOT_FOUND, ex.errorCode());
        verify(client, never()).paymentStatus(anyString());
    }

    @Test
    @DisplayName("status by-reference: with several attempts per reference the APPROVED one wins")
    void statusByReference_prefersApprovedAttempt() {
        SmnPaymentEntity newest = new SmnPaymentEntity("SMN-NEWEST", "qr", "m", "UB Store", null);
        newest.setHubReference("ref-dup");
        SmnPaymentEntity approved = new SmnPaymentEntity(TOKEN, "qr", "m", "UB Store", null);
        approved.setHubReference("ref-dup");
        approved.setStatus(SmnPaymentEntity.Status.APPROVED);
        when(payments.findByHubReferenceOrderByIdDesc("ref-dup"))
                .thenReturn(java.util.List.of(newest, approved));
        when(client.paymentStatus(TOKEN)).thenReturn(statusOf("Approved"));

        StatusResponse resp = adapter.statusByReference("ref-dup");

        assertEquals(TOKEN, resp.txTokenNo(), "the attempt whose money moved must answer");
        assertEquals("APPROVED", resp.status());
    }

    @Test
    @DisplayName("submit-mpm: backfills a hub reference the caller omitted at verify-qr")
    void submit_backfillsHubReference() {
        SmnPaymentEntity p = verifiedPayment();
        registeredRate("3373.000000");
        when(client.confirm(any())).thenReturn(new ConfirmApiResponse(
                "0", "success", null, "PN-1", "GME1453767113", "12345", "UB Store"));

        adapter.submitMpm(new SubmitMpmRequest(TOKEN, "10000.00", null, "ref-late"));

        assertEquals("ref-late", p.getHubReference());
    }
}
