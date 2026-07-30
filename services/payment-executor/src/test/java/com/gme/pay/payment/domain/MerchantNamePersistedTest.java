package com.gme.pay.payment.domain;

import com.gme.pay.payment.domain.client.PartnerConfigClient;
import com.gme.pay.payment.domain.client.PrefundingClient;
import com.gme.pay.payment.domain.client.QrClient;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4-4 — every corridor PERSISTS the merchant name it resolved, and persists null when it genuinely
 * resolved nothing.
 *
 * <p>The gap was never "the corridors don't know who the merchant is" — all three did, at payment
 * time. It was that the name rode only the synchronous wallet response and was never put on the
 * create contract, so every later read (receipt, portal detail, admin drawer) had nothing to show.
 * These tests therefore assert on the {@link TransactionClient.CreateRequest} actually sent, which is
 * the exact hop where the name used to be dropped.
 *
 * <p>The second half of each corridor's pair is the honesty rule: when the name is unknown the
 * request must carry {@code null} — never the merchant id, never a synthesised label, never the
 * lenient/dev-synth {@code "Unknown Merchant"} placeholder. A fabricated name on a receipt is a
 * factual claim about who was paid, and it would also make this very gap look closed.
 */
class MerchantNamePersistedTest {

    private static TransactionClient.CreateRequest capturedCreate(TransactionClient client) {
        ArgumentCaptor<TransactionClient.CreateRequest> captor =
                ArgumentCaptor.forClass(TransactionClient.CreateRequest.class);
        verify(client).createPending(captor.capture());
        return captor.getValue();
    }

    // =========================================================================
    // GMEREMIT / ZeroPay domestic — name comes from merchant-qr-data
    // =========================================================================

    @Nested
    @DisplayName("GMEREMIT (domestic ZeroPay)")
    class Gmeremit {

        private QrClient qrClient;
        private SchemeClient schemeClient;
        private TransactionClient transactionClient;
        private ExecutionAttemptRepository attempts;

        @BeforeEach
        void setUp() {
            qrClient = mock(QrClient.class);
            schemeClient = mock(SchemeClient.class);
            transactionClient = mock(TransactionClient.class);
            attempts = mock(ExecutionAttemptRepository.class);

            when(schemeClient.submitMpm(any())).thenReturn(
                    new SchemeClient.MpmSubmitResponse("AUTH-1", "ZP-TXN-1", Instant.now()));
            when(transactionClient.createPending(any())).thenReturn(
                    new TransactionClient.CreateResult("TXN-KR-1", "PAY-1", Instant.now()));
        }

        private GmeremitPaymentService service(boolean devSynthMerchant) {
            return new GmeremitPaymentService(qrClient, schemeClient, attempts, devSynthMerchant,
                    transactionClient, null, null);
        }

        @Test
        @DisplayName("persists the merchant-qr-data name onto the transaction")
        void persistsResolvedName() {
            when(qrClient.resolve(anyString())).thenReturn(new QrClient.MerchantView(
                    "M001", "Gangnam Coffee House", "KRW", "zeropay", "RETAIL", true));

            GmeremitPaymentService.WalletResult result =
                    service(false).pay("ZPQR", new BigDecimal("10000"), "user-kr");

            assertTrue(result.approved());
            assertEquals("Gangnam Coffee House", capturedCreate(transactionClient).merchantName(),
                    "the name the hub resolved must reach transaction-mgmt, not just the caller");
        }

        @Test
        @DisplayName("dev-synth placeholder is NOT persisted as a real name (null instead)")
        void devSynthPlaceholderIsNotPersisted() {
            // merchant-qr-data unreachable + the DEV escape hatch ⇒ the service synthesises
            // "Unknown Merchant" so the payment can proceed. That is a statement about our lookup,
            // not about the merchant, so it must not be stored as the merchant's name.
            when(qrClient.resolve(anyString())).thenThrow(new RuntimeException("qr-data down"));

            GmeremitPaymentService.WalletResult result =
                    service(true).pay("ZPQR", new BigDecimal("10000"), "user-kr");

            assertTrue(result.approved());
            TransactionClient.CreateRequest req = capturedCreate(transactionClient);
            assertNull(req.merchantName(),
                    "\"Unknown Merchant\" must never be persisted — a merchant could legitimately "
                            + "be named that, and then the two cases are indistinguishable");
            assertEquals("UNKNOWN", req.merchantId(),
                    "the synthesised id still rides its own field; only the NAME is suppressed");
            // The caller still sees the placeholder — it explains WHY the merchant is unnamed.
            assertEquals(MerchantNames.PLACEHOLDER, result.merchantName());
        }
    }

    // =========================================================================
    // SENDMN (Mongolia) — the scheme's own verify-qr name is authoritative
    // =========================================================================

    @Nested
    @DisplayName("SENDMN (Mongolia KRW→MNT)")
    class Sendmn {

        private QrClient qrClient;
        private RateClient rateClient;
        private PrefundingClient prefundingClient;
        private SchemeClient schemeClient;
        private TransactionClient transactionClient;
        private ExecutionAttemptRepository attempts;
        private RevenueLedgerClient revenue;

        @BeforeEach
        void setUp() {
            qrClient = mock(QrClient.class);
            rateClient = mock(RateClient.class);
            prefundingClient = mock(PrefundingClient.class);
            schemeClient = mock(SchemeClient.class);
            transactionClient = mock(TransactionClient.class);
            attempts = mock(ExecutionAttemptRepository.class);
            revenue = mock(RevenueLedgerClient.class);

            when(rateClient.fetchLiveRate("KRW", "MNT")).thenReturn(
                    new RateClient.LiveRate("KRW", "MNT", new BigDecimal("3.5"), Instant.now(), "sim"));
            when(prefundingClient.deduct(anyLong(), anyString(), any())).thenReturn(
                    new PrefundingClient.DeductionResult(new BigDecimal("7.5"), new BigDecimal("100")));
            when(transactionClient.createPending(any())).thenReturn(
                    new TransactionClient.CreateResult("TXN-MN-1", "PAY-1", Instant.now()));
        }

        private SendmnPaymentService service(boolean lenient) {
            return new SendmnPaymentService(qrClient, rateClient, prefundingClient, schemeClient,
                    attempts, lenient, new BigDecimal("0.02"), transactionClient, revenue);
        }

        @Test
        @DisplayName("persists the name the adapter's verify-qr returned, preferring it over the hub cache")
        void prefersTheSchemeName() {
            when(qrClient.resolve(anyString())).thenReturn(new QrClient.MerchantView(
                    "M001", "Stale Cached Name", "MNT", "sendmn", "RETAIL", true));
            // The adapter client now carries verify-qr's MERCHANT_NAME on the submit response; it used
            // to be decoded and then dropped between verify-qr and Confirm.
            when(schemeClient.submitMpm(any())).thenReturn(new SchemeClient.MpmSubmitResponse(
                    "APPROVED", "MN-PAY-1", Instant.now(), "UB Central Store"));

            GmeremitPaymentService.WalletResult result =
                    service(false).pay("ZPQR_MNT", new BigDecimal("10000"), "user-mn", 2L);

            assertTrue(result.approved());
            assertEquals("UB Central Store", capturedCreate(transactionClient).merchantName(),
                    "SendMN's own answer is the name the SCHEME shows, so it outranks our cached row");
            assertEquals("UB Central Store", result.merchantName(),
                    "the synchronous response must agree with what was persisted");
        }

        @Test
        @DisplayName("falls back to the hub's merchant-qr-data name when the scheme reports none")
        void fallsBackToHubName() {
            when(qrClient.resolve(anyString())).thenReturn(new QrClient.MerchantView(
                    "M001", "MNT Merchant", "MNT", "sendmn", "RETAIL", true));
            when(schemeClient.submitMpm(any())).thenReturn(
                    new SchemeClient.MpmSubmitResponse("APPROVED", "MN-PAY-1", Instant.now()));

            service(false).pay("ZPQR_MNT", new BigDecimal("10000"), "user-mn", 2L);

            assertEquals("MNT Merchant", capturedCreate(transactionClient).merchantName());
        }

        @Test
        @DisplayName("lenient-mode \"Unknown Merchant\" is not persisted, and no id is substituted")
        void lenientPlaceholderIsNotPersisted() {
            // Lenient mode: merchant-qr-data unreachable ⇒ MerchantView("UNKNOWN","Unknown Merchant").
            when(qrClient.resolve(anyString())).thenThrow(new RuntimeException("qr-data down"));
            when(schemeClient.submitMpm(any())).thenReturn(
                    new SchemeClient.MpmSubmitResponse("APPROVED", "MN-PAY-1", Instant.now()));

            service(true).pay("ZPQR_MNT", new BigDecimal("10000"), "user-mn", 2L);

            TransactionClient.CreateRequest req = capturedCreate(transactionClient);
            assertNull(req.merchantName(), "the lenient placeholder must not become a receipt value");
            assertEquals("UNKNOWN", req.merchantId());
        }
    }

    // =========================================================================
    // NEPAL — only the adapter can name the receiver (no hub-side merchant lookup)
    // =========================================================================

    @Nested
    @DisplayName("NEPAL (KRW→NPR)")
    class Nepal {

        private static final String FONEPAY_QR =
                "00020101021126150011fonepay.com5802NP5910KINAUN PVT6304ABCD";

        private RateClient rateClient;
        private PartnerConfigClient partnerConfigClient;
        private PrefundingClient prefundingClient;
        private SchemeClient schemeClient;
        private TransactionClient transactionClient;
        private ExecutionAttemptRepository attempts;

        @BeforeEach
        void setUp() {
            rateClient = mock(RateClient.class);
            partnerConfigClient = mock(PartnerConfigClient.class);
            prefundingClient = mock(PrefundingClient.class);
            schemeClient = mock(SchemeClient.class);
            transactionClient = mock(TransactionClient.class);
            attempts = mock(ExecutionAttemptRepository.class);

            when(rateClient.fetchLiveRate("KRW", "NPR")).thenReturn(new RateClient.LiveRate(
                    "KRW", "NPR", new BigDecimal("0.10"), Instant.now(), "sim"));
            when(rateClient.fetchLiveRate("USD", "KRW")).thenReturn(new RateClient.LiveRate(
                    "USD", "KRW", new BigDecimal("1350"), Instant.now(), "sim"));
            when(prefundingClient.deduct(anyLong(), anyString(), any())).thenReturn(
                    new PrefundingClient.DeductionResult(
                            new BigDecimal("74.44444444"), new BigDecimal("1000")));
            when(schemeClient.submitMpm(any())).thenReturn(
                    new SchemeClient.MpmSubmitResponse("APPROVED", "NP-IDX-1", Instant.now()));
            when(transactionClient.createPending(any())).thenReturn(
                    new TransactionClient.CreateResult("TXN-NP-1", "PAY-1", Instant.now()));
            when(partnerConfigClient.resolveLimits(anyString())).thenReturn(Optional.empty());
        }

        private NepalPaymentService service() {
            return new NepalPaymentService(schemeClient, attempts,
                    new NepalCorridorPricing(rateClient, partnerConfigClient, "0.02", "500"),
                    prefundingClient, transactionClient, null, null, WalletLimitGate.disabled());
        }

        private GmeremitPaymentService.WalletResult pay() {
            return service().pay(FONEPAY_QR, new BigDecimal("100000"), "KRW", "user-np",
                    WalletPartnerRef.of("GMEREMIT", 1L));
        }

        @Test
        @DisplayName("asks the adapter to decode the receiver name and persists it")
        void persistsTheAdapterDecodedName() {
            // The Nepal corridor performs NO hub merchant lookup, so the adapter's decode is the only
            // source of the name — hence the explicit resolveMerchantName hop.
            // "NEPAL" is the ROUTER key (SchemeClientRouter dispatches on the scheme CODE), the same
            // value this corridor already passes to submitMpm/lookupStatus.
            when(schemeClient.resolveMerchantName("NEPAL", FONEPAY_QR)).thenReturn("Kinaun Pvt Ltd");

            GmeremitPaymentService.WalletResult result = pay();

            assertTrue(result.approved());
            assertEquals("Kinaun Pvt Ltd", capturedCreate(transactionClient).merchantName());
            assertEquals("Kinaun Pvt Ltd", result.merchantName(),
                    "and the wallet response now carries it too (was a hardcoded null + TODO)");
        }

        @Test
        @DisplayName("an unavailable decode leaves the name null and does NOT fail the payment")
        void decodeFailureDegradesToNull() {
            // resolveMerchantName is contractually non-throwing, but the corridor must also be safe if
            // an implementation ever answers null: a display lookup can never cost a customer a payment.
            when(schemeClient.resolveMerchantName(anyString(), anyString())).thenReturn(null);

            GmeremitPaymentService.WalletResult result = pay();

            assertTrue(result.approved(), "the payment stands — the name is cosmetic, the money is not");
            assertNull(capturedCreate(transactionClient).merchantName());
        }
    }

    // =========================================================================
    // The honesty guard itself
    // =========================================================================

    @Nested
    @DisplayName("MerchantNames guard")
    class Guard {

        @Test
        @DisplayName("returns the first REAL candidate, in authority order")
        void picksTheFirstRealCandidate() {
            assertEquals("Scheme Name", MerchantNames.realOrNull("Scheme Name", "Hub Name"));
            assertEquals("Hub Name", MerchantNames.realOrNull(null, "Hub Name"));
            assertEquals("Hub Name", MerchantNames.realOrNull("   ", "Hub Name"));
            assertEquals("Hub Name", MerchantNames.realOrNull(MerchantNames.PLACEHOLDER, "Hub Name"));
        }

        @Test
        @DisplayName("rejects placeholders and blanks rather than passing them on")
        void rejectsPlaceholders() {
            assertNull(MerchantNames.realOrNull(MerchantNames.PLACEHOLDER));
            assertNull(MerchantNames.realOrNull("unknown merchant"));   // case-insensitive
            assertNull(MerchantNames.realOrNull("Unknown"));
            assertNull(MerchantNames.realOrNull("null"));
            assertNull(MerchantNames.realOrNull(""));
            assertNull(MerchantNames.realOrNull("   "));
            assertNull(MerchantNames.realOrNull((String[]) null));
            assertNull(MerchantNames.realOrNull());
        }

        @Test
        @DisplayName("trims, and keeps names that merely CONTAIN a placeholder word")
        void keepsLegitimateNames() {
            assertEquals("UB Store", MerchantNames.realOrNull("  UB Store  "));
            // Only the exact placeholder is refused; a real shop may be named around those words.
            assertEquals("Unknown Merchant Coffee",
                    MerchantNames.realOrNull("Unknown Merchant Coffee"));
            assertTrue(MerchantNames.isReal("Unknown Merchant Coffee"));
        }
    }
}
