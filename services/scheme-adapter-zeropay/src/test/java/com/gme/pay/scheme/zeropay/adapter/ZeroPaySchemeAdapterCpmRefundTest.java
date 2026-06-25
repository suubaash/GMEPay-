package com.gme.pay.scheme.zeropay.adapter;

import com.gme.pay.scheme.zeropay.adapter.model.CancelResult;
import com.gme.pay.scheme.zeropay.adapter.model.CpmAuthRequest;
import com.gme.pay.scheme.zeropay.adapter.model.CpmAuthResponse;
import com.gme.pay.scheme.zeropay.adapter.model.MerchantIdentifier;
import com.gme.pay.scheme.zeropay.adapter.model.PrepareToken;
import com.gme.pay.scheme.zeropay.batch.ZpBatchDataPort;
import com.gme.pay.scheme.zeropay.client.ZeroPaySchemeApiClient;
import com.gme.pay.scheme.zeropay.sftp.SftpTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the CPM (prepareCPM + authoriseCpm) and refund (cancelPayment) paths
 * in {@link ZeroPaySchemeAdapter}.
 *
 * <p>No Spring context — pure JUnit 5 + Mockito.
 */
@ExtendWith(MockitoExtension.class)
class ZeroPaySchemeAdapterCpmRefundTest {

    @Mock
    private ZeroPayAdapterProperties properties;

    @Mock
    private ZeroPaySchemeApiClient schemeApiClient;

    @Mock
    private SftpTransport sftpTransport;

    @Mock
    private ZpBatchDataPort batchDataPort;

    private ZeroPaySchemeAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new ZeroPaySchemeAdapter(properties, schemeApiClient, sftpTransport, batchDataPort);
    }

    // -----------------------------------------------------------------------
    // prepareCPM — happy path
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("prepareCPM: calls fetchCpmToken and returns PrepareToken with cpmToken as tokenId")
    void prepareCPM_happyPath() {
        MerchantIdentifier merchant = new MerchantIdentifier(
                "M001", "QR_HASH_001", "Test Merchant", "static");

        ZeroPaySchemeApiClient.CpmTokenResponse tokenResp =
                new ZeroPaySchemeApiClient.CpmTokenResponse(
                        "CPM",
                        "CPM-TOKEN-AABB1122",
                        "2026-06-15T14:05:00+09:00"
                );
        when(schemeApiClient.fetchCpmToken(eq("M001"), eq("WALLET")))
                .thenReturn(tokenResp);

        PrepareToken token = adapter.prepareCPM(merchant);

        assertNotNull(token);
        assertEquals("CPM-TOKEN-AABB1122", token.tokenId());
        assertEquals("M001", token.merchantId());
        assertNotNull(token.expiresAt());
        // expiresAt should be parsed from the ISO string (date-robust: assert the exact parsed
        // instant, not relative to Instant.now() — the latter rots once the clock passes the date).
        assertEquals(Instant.parse("2026-06-15T05:05:00Z"), token.expiresAt());
        verify(schemeApiClient).fetchCpmToken("M001", "WALLET");
    }

    @Test
    @DisplayName("prepareCPM: falls back to Instant.now()+300s when expiresAt is unparseable")
    void prepareCPM_unparseableExpiresAt_fallback() {
        MerchantIdentifier merchant = new MerchantIdentifier(
                "M002", "QR_HASH_002", "Merchant B", "static");

        ZeroPaySchemeApiClient.CpmTokenResponse tokenResp =
                new ZeroPaySchemeApiClient.CpmTokenResponse(
                        "CPM",
                        "CPM-TOKEN-CCDD3344",
                        "NOT_A_VALID_INSTANT"
                );
        when(schemeApiClient.fetchCpmToken(eq("M002"), eq("WALLET")))
                .thenReturn(tokenResp);

        PrepareToken token = adapter.prepareCPM(merchant);

        assertNotNull(token.expiresAt());
        // Should be approximately now + 300s
        assertTrue(token.expiresAt().isAfter(Instant.now().plusSeconds(250)));
    }

    // -----------------------------------------------------------------------
    // authoriseCpm — happy path (authorize + commit)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("authoriseCpm: calls authorize(CPM, null, cpmToken, ...) then commit, returns approval")
    void authoriseCpm_happyPath() {
        ZeroPaySchemeApiClient.AuthorizeResponse authResp =
                new ZeroPaySchemeApiClient.AuthorizeResponse(
                        "AUTH-CPM-001",
                        "APPROVED",
                        "SCHEME-REF-001",
                        "M001",
                        new BigDecimal("50000"),
                        "KRW",
                        "2026-06-15T13:00:00+09:00"
                );
        ZeroPaySchemeApiClient.CommitResponse commitResp =
                new ZeroPaySchemeApiClient.CommitResponse(
                        "AUTH-CPM-001",
                        "CAPTURED",
                        "TXN-CPM-AABB001",
                        "2026-06-15T13:00:05+09:00"
                );

        when(schemeApiClient.authorize(eq("CPM"), isNull(), eq("CPM-TOKEN-AABB1122"),
                eq(new BigDecimal("50000")), eq("KRW"), eq("PARTNER-TXN-001")))
                .thenReturn(authResp);
        when(schemeApiClient.commit(eq("AUTH-CPM-001")))
                .thenReturn(commitResp);

        CpmAuthRequest request = new CpmAuthRequest(
                "M001",
                "CPM-TOKEN-AABB1122",
                new BigDecimal("50000"),
                "PARTNER-TXN-001",
                null
        );

        CpmAuthResponse response = adapter.authoriseCpm(request);

        assertNotNull(response);
        assertEquals("AUTH-CPM-001", response.approvalCode());
        assertEquals("TXN-CPM-AABB001", response.zeroPayTxnRef());
        assertEquals("00", response.resultCode());
        assertEquals("CAPTURED", response.resultMessage());
        verify(schemeApiClient).authorize("CPM", null, "CPM-TOKEN-AABB1122",
                new BigDecimal("50000"), "KRW", "PARTNER-TXN-001");
        verify(schemeApiClient).commit("AUTH-CPM-001");
    }

    // -----------------------------------------------------------------------
    // cancelPayment (refund) — happy path
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("cancelPayment: calls refund with authId and null amount, returns REFUNDED")
    void cancelPayment_happyPath() {
        ZeroPaySchemeApiClient.RefundResponse refundResp =
                new ZeroPaySchemeApiClient.RefundResponse("REFUND-001", "REFUNDED");
        when(schemeApiClient.refund(eq("AUTH-CPM-001"), isNull()))
                .thenReturn(refundResp);

        CancelResult result = adapter.cancelPayment("AUTH-CPM-001");

        assertTrue(result.success());
        assertEquals("REFUNDED", result.schemeResultCode());
        assertEquals("REFUND-001", result.cancellationRef());
        verify(schemeApiClient).refund("AUTH-CPM-001", null);
    }

    @Test
    @DisplayName("cancelPayment: when scheme returns non-REFUNDED status, success=false")
    void cancelPayment_schemeReturnsError_successFalse() {
        ZeroPaySchemeApiClient.RefundResponse refundResp =
                new ZeroPaySchemeApiClient.RefundResponse("REFUND-002", "DECLINED");
        when(schemeApiClient.refund(eq("AUTH-CPM-999"), isNull()))
                .thenReturn(refundResp);

        CancelResult result = adapter.cancelPayment("AUTH-CPM-999");

        assertFalse(result.success());
        assertEquals("DECLINED", result.schemeResultCode());
    }
}
