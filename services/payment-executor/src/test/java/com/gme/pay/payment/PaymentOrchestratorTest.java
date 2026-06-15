package com.gme.pay.payment;

import com.gme.pay.payment.domain.InsufficientPrefundingException;
import com.gme.pay.payment.domain.PartnerType;
import com.gme.pay.payment.domain.PaymentOrchestrator;
import com.gme.pay.payment.domain.PaymentOrchestrator.MpmPaymentCommand;
import com.gme.pay.payment.domain.PaymentOrchestrator.PaymentResult;
import com.gme.pay.payment.domain.PaymentStatus;
import com.gme.pay.payment.domain.SchemeDeclinedException;
import com.gme.pay.payment.domain.client.PrefundingClient;
import com.gme.pay.payment.domain.client.QrClient;
import com.gme.pay.payment.domain.client.RateClient;
import com.gme.pay.payment.domain.client.SchemeClient;
import com.gme.pay.payment.domain.client.TransactionClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Plain JUnit 5 unit tests for {@link PaymentOrchestrator}.
 * No Spring context, no Docker, no Testcontainers.
 * All collaborators are hand-written fakes that record call order.
 */
class PaymentOrchestratorTest {

    // ---- hand-written fakes ----

    /** Records the sequence of service calls so tests can assert ordering. */
    private final List<String> callLog = new ArrayList<>();

    private final RateClient fakeRate = (quoteId, partnerId) -> {
        callLog.add("RATE:" + quoteId);
        return new RateClient.RateQuoteView(
                quoteId, partnerId, "zeropay", "inbound",
                new BigDecimal("50000"), "KRW",
                new BigDecimal("37.015197"), new BigDecimal("35.562589"),
                new BigDecimal("0.925380"), new BigDecimal("0.370152"),
                new BigDecimal("37.015197"), new BigDecimal("0.35"),
                new BigDecimal("37.365197"), "USD",
                new BigDecimal("1.025610"), new BigDecimal("1351.00"),
                Instant.now().plusSeconds(300), false
        );
    };

    private final QrClient fakeQr = qr -> {
        callLog.add("QR:" + qr);
        return QrClient.MerchantView.of("M001", "Test Merchant", "KRW", "zeropay");
    };

    private final TransactionClient fakeTxn = new TransactionClient() {
        @Override
        public CreateResult createPending(CreateRequest req) {
            callLog.add("TXN:CREATE");
            return new CreateResult("txn_001", "pay_001", Instant.now());
        }

        @Override
        public void commitStatus(String txnRef, StatusPatch patch) {
            callLog.add("TXN:COMMIT:" + patch.newStatus().name());
        }
    };

    /** Normal prefunding fake — deduction succeeds. */
    private final PrefundingClient fakePrefunding = new PrefundingClient() {
        @Override
        public DeductionResult deduct(long partnerId, String txnRef, BigDecimal amountUsd) {
            callLog.add("PREFUND:DEDUCT");
            return new DeductionResult(amountUsd, new BigDecimal("962.985"));
        }

        @Override
        public void reverse(long partnerId, String txnRef) {
            callLog.add("PREFUND:REVERSE");
        }
    };

    /** Prefunding fake that always rejects (balance too low). */
    private final PrefundingClient insufficientPrefunding = new PrefundingClient() {
        @Override
        public DeductionResult deduct(long partnerId, String txnRef, BigDecimal amountUsd) {
            callLog.add("PREFUND:DEDUCT_FAIL");
            throw new InsufficientPrefundingException(new BigDecimal("5.00"), amountUsd);
        }

        @Override
        public void reverse(long partnerId, String txnRef) {
            callLog.add("PREFUND:REVERSE");
        }
    };

    /** Scheme fake that returns an approval. */
    private final SchemeClient fakeScheme = new SchemeClient() {
        @Override
        public MpmSubmitResponse submitMpm(MpmSubmitRequest req) {
            callLog.add("SCHEME:SUBMIT");
            return new MpmSubmitResponse(
                    "ZP20260608093115001234",
                    "ZP_TXN_" + req.txnRef(),
                    Instant.now()
            );
        }

        @Override
        public void cancelPayment(String schemeTxnRef, String reason) {
            callLog.add("SCHEME:CANCEL");
        }

        @Override
        public CpmSubmitResponse submitCpm(CpmSubmitRequest req) {
            callLog.add("SCHEME:CPM_SUBMIT");
            return new CpmSubmitResponse("ZP_CPM_APPROVAL", "ZP_CPM_TXN", Instant.now());
        }
    };

    /** Scheme fake that always declines. */
    private final SchemeClient decliningScheme = new SchemeClient() {
        @Override
        public MpmSubmitResponse submitMpm(MpmSubmitRequest req) {
            callLog.add("SCHEME:SUBMIT_DECLINE");
            throw new SchemeDeclinedException("ZP_ERR_001", "Transaction declined by scheme");
        }

        @Override
        public void cancelPayment(String schemeTxnRef, String reason) {}

        @Override
        public CpmSubmitResponse submitCpm(CpmSubmitRequest req) {
            throw new SchemeDeclinedException("ZP_ERR_CPM_001", "CPM declined");
        }
    };

    private MpmPaymentCommand sampleCommand;

    @BeforeEach
    void setUp() {
        callLog.clear();
        sampleCommand = new MpmPaymentCommand(
                42L, "qte_01HX7MNP9AB2CDEF3GH456IJ",
                "ZPQR00012345678901234567890",
                "zeropay", "inbound",
                "customer-001", "PTNR_TXN_001", "PARTNER_42"
        );
    }

    // ======================================================================
    // Test 1: OVERSEAS happy path — prefunding deduction BEFORE scheme call
    // ======================================================================

    @Test
    @DisplayName("OVERSEAS happy-path: prefunding deducted before scheme submit")
    void overseas_happyPath_callOrderIsCorrect() {
        PaymentOrchestrator orchestrator = new PaymentOrchestrator(
                fakeRate, fakePrefunding, fakeQr, fakeScheme, fakeTxn);

        PaymentResult result = orchestrator.executeMpm(sampleCommand, PartnerType.OVERSEAS);

        // Assert final outcome
        assertNotNull(result.paymentId());
        assertEquals(PaymentStatus.APPROVED, result.status());
        assertNotNull(result.prefundDeductedUsd(), "OVERSEAS payment must include prefundDeductedUsd");

        // Assert strict call order: RATE -> QR -> TXN:CREATE -> PREFUND:DEDUCT -> SCHEME:SUBMIT -> TXN:COMMIT
        int rateIdx      = indexOf("RATE:qte_01HX7MNP9AB2CDEF3GH456IJ");
        int qrIdx        = indexOf("QR:ZPQR00012345678901234567890");
        int txnCreateIdx = indexOf("TXN:CREATE");
        int prefundIdx   = indexOf("PREFUND:DEDUCT");
        int schemeIdx    = indexOf("SCHEME:SUBMIT");
        int txnCommitIdx = indexOf("TXN:COMMIT:APPROVED");

        assertOrder("RATE before PREFUND", rateIdx, prefundIdx);
        assertOrder("QR before PREFUND", qrIdx, prefundIdx);
        assertOrder("TXN:CREATE before PREFUND", txnCreateIdx, prefundIdx);
        assertOrder("PREFUND before SCHEME", prefundIdx, schemeIdx);
        assertOrder("SCHEME before TXN:COMMIT", schemeIdx, txnCommitIdx);
    }

    // ======================================================================
    // Test 2: Insufficient prefunding must stop BEFORE the scheme is called
    // ======================================================================

    @Test
    @DisplayName("Insufficient prefunding: scheme is NEVER called")
    void overseas_insufficientPrefunding_schemeNeverCalled() {
        PaymentOrchestrator orchestrator = new PaymentOrchestrator(
                fakeRate, insufficientPrefunding, fakeQr, fakeScheme, fakeTxn);

        assertThrows(InsufficientPrefundingException.class,
                () -> orchestrator.executeMpm(sampleCommand, PartnerType.OVERSEAS));

        // Prefund attempt must be in the log
        assertPresent("PREFUND:DEDUCT_FAIL");

        // Scheme must NOT appear in the log at all
        assertAbsent("SCHEME:SUBMIT", "Scheme must never be called when prefunding is insufficient");
    }

    // ======================================================================
    // Test 3: LOCAL partner skips prefunding entirely
    // ======================================================================

    @Test
    @DisplayName("LOCAL partner: prefunding is never touched")
    void local_happyPath_prefundingSkipped() {
        // Use a prefunding fake that would throw if called
        PrefundingClient boom = new PrefundingClient() {
            @Override
            public DeductionResult deduct(long partnerId, String txnRef, BigDecimal amountUsd) {
                throw new AssertionError("PrefundingClient.deduct must NOT be called for LOCAL partners");
            }

            @Override
            public void reverse(long partnerId, String txnRef) {
                throw new AssertionError("PrefundingClient.reverse must NOT be called for LOCAL partners");
            }
        };

        PaymentOrchestrator orchestrator = new PaymentOrchestrator(
                fakeRate, boom, fakeQr, fakeScheme, fakeTxn);

        PaymentResult result = orchestrator.executeMpm(sampleCommand, PartnerType.LOCAL);

        assertEquals(PaymentStatus.APPROVED, result.status());
        // LOCAL: prefundDeductedUsd must be null
        assertEquals(null, result.prefundDeductedUsd(),
                "LOCAL payment must NOT include prefundDeductedUsd");
    }

    // ======================================================================
    // Test 4: Scheme decline reverses prefunding for OVERSEAS
    // ======================================================================

    @Test
    @DisplayName("Scheme decline: OVERSEAS prefunding is reversed immediately")
    void overseas_schemeDecline_prefundingReversed() {
        PaymentOrchestrator orchestrator = new PaymentOrchestrator(
                fakeRate, fakePrefunding, fakeQr, decliningScheme, fakeTxn);

        assertThrows(SchemeDeclinedException.class,
                () -> orchestrator.executeMpm(sampleCommand, PartnerType.OVERSEAS));

        // Deduction happened first
        assertPresent("PREFUND:DEDUCT");
        // Reversal happened after decline
        assertPresent("PREFUND:REVERSE");
        // Deduct comes before reverse
        assertOrder("DEDUCT before REVERSE",
                indexOf("PREFUND:DEDUCT"), indexOf("PREFUND:REVERSE"));
    }

    // ---- helpers ----

    private int indexOf(String step) {
        int idx = callLog.indexOf(step);
        if (idx < 0) {
            throw new AssertionError("Expected call log to contain '" + step
                    + "' but log was: " + callLog);
        }
        return idx;
    }

    private void assertOrder(String description, int earlier, int later) {
        if (earlier >= later) {
            throw new AssertionError(description + ": expected index " + earlier
                    + " < " + later + " in log " + callLog);
        }
    }

    private void assertPresent(String step) {
        if (!callLog.contains(step)) {
            throw new AssertionError("Expected '" + step + "' in call log but got: " + callLog);
        }
    }

    private void assertAbsent(String step, String message) {
        if (callLog.contains(step)) {
            throw new AssertionError(message + " — but '" + step
                    + "' was found in call log: " + callLog);
        }
    }
}
