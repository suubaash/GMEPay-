package com.gme.pay.payment.domain;

import com.gme.pay.payment.domain.client.QrClient;
import com.gme.pay.payment.domain.client.SchemeClient;
import com.gme.pay.payment.persistence.ExecutionAttemptRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 2 hardening of the lenient fake-merchant bypass in {@link GmeremitPaymentService#pay}.
 *
 * <p>STRICT is the only non-dev behavior: a merchant-qr-data miss/unreachable HARD-FAILS with
 * {@link MerchantNotFoundException} (never synthesizes an UNKNOWN merchant, never reaches the scheme).
 * Synthesizing a placeholder merchant is gated on the explicit {@code dev-synth-merchant} flag.
 */
class GmeremitPaymentServiceMerchantStrictTest {

    @Test
    void strictMode_merchantLookupFails_hardFailsWithoutSchemeCall() {
        QrClient qr = mock(QrClient.class);
        SchemeClient scheme = mock(SchemeClient.class);
        ExecutionAttemptRepository repo = mock(ExecutionAttemptRepository.class);
        when(qr.resolve(anyString()))
                .thenThrow(new PaymentException("merchant-qr-data GET /v1/merchants/QR failed: 404"));

        // devSynthMerchant = false -> STRICT (default, non-dev).
        GmeremitPaymentService svc = new GmeremitPaymentService(qr, scheme, repo, false);

        MerchantNotFoundException ex = assertThrows(MerchantNotFoundException.class,
                () -> svc.pay("QR", new BigDecimal("50000"), "user-1"));
        assertTrue(ex.getMessage().contains("strict"));
        // No synthesized merchant, so the scheme is never hit.
        verify(scheme, never()).submitMpm(any());
    }

    @Test
    void devSynthFlagOn_merchantLookupFails_synthesizesUnknownAndProceeds() {
        QrClient qr = mock(QrClient.class);
        SchemeClient scheme = mock(SchemeClient.class);
        ExecutionAttemptRepository repo = mock(ExecutionAttemptRepository.class);
        when(qr.resolve(anyString())).thenThrow(new PaymentException("merchant-qr-data unreachable"));
        when(scheme.submitMpm(any()))
                .thenReturn(new SchemeClient.MpmSubmitResponse("ZP_AUTH", "ZP_TXN", Instant.now()));

        // devSynthMerchant = true -> DEV escape hatch: synth UNKNOWN merchant, payment proceeds.
        GmeremitPaymentService svc = new GmeremitPaymentService(qr, scheme, repo, true);

        GmeremitPaymentService.WalletResult r = svc.pay("QR", new BigDecimal("50000"), "user-1");
        assertTrue(r.approved());
        assertEquals("Unknown Merchant", r.merchantName());
        verify(scheme).submitMpm(any());
    }
}
