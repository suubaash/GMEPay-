package com.gme.pay.payment.domain;

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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link PaymentOrchestrator#executeCpm(PaymentOrchestrator.CpmPaymentCommand, PartnerType)}.
 *
 * <p>No Spring context, no Docker. Hand-written fakes record call order.
 */
class PaymentOrchestratorCpmTest {

    private final List<String> callLog = new ArrayList<>();

    /** Fake rate client — returns a no-op view (not called by CPM path). */
    private final RateClient fakeRate = (quoteId, partnerId) -> {
        callLog.add("RATE");
        return null;
    };

    /** Fake QR client — not called by CPM path. */
    private final QrClient fakeQr = qr -> {
        callLog.add("QR");
        return QrClient.MerchantView.of("M001", "Merchant", "KRW", "zeropay");
    };

    private final TransactionClient fakeTxn = new TransactionClient() {
        @Override
        public CreateResult createPending(CreateRequest req) {
            callLog.add("TXN:CREATE:mode=" + req.paymentMode());
            return new CreateResult("txn_cpm_001", "pay_cpm_001", Instant.now());
        }

        @Override
        public void commitStatus(String txnRef, StatusPatch patch) {
            callLog.add("TXN:COMMIT:" + patch.newStatus().name());
        }
    };

    private final PrefundingClient fakePrefunding = new PrefundingClient() {
        @Override
        public DeductionResult deduct(long partnerId, String txnRef, BigDecimal amountUsd) {
            callLog.add("PREFUND:DEDUCT");
            return new DeductionResult(amountUsd, new BigDecimal("900.00"));
        }

        @Override
        public ReverseResult reverse(long partnerId, String txnRef) {
            callLog.add("PREFUND:REVERSE");
            return new ReverseResult(new BigDecimal("125.50"), new BigDecimal("900.00"));
        }
    };

    private final SchemeClient fakeScheme = new SchemeClient() {
        @Override
        public MpmSubmitResponse submitMpm(MpmSubmitRequest req) {
            callLog.add("SCHEME:MPM");
            return new MpmSubmitResponse("ZP_APPROVAL", "ZP_TXN", Instant.now());
        }

        @Override
        public void cancelPayment(String schemeTxnRef, String reason) {
            callLog.add("SCHEME:CANCEL");
        }

        @Override
        public CpmSubmitResponse submitCpm(CpmSubmitRequest req) {
            callLog.add("SCHEME:CPM:token=" + req.qrToken());
            return new CpmSubmitResponse("ZP_CPM_APPROVAL", "ZP_CPM_TXN_001", Instant.now());
        }
    };

    private final SchemeClient decliningScheme = new SchemeClient() {
        @Override
        public MpmSubmitResponse submitMpm(MpmSubmitRequest req) {
            throw new SchemeDeclinedException("ZP_ERR_001", "declined");
        }

        @Override
        public void cancelPayment(String schemeTxnRef, String reason) {}

        @Override
        public CpmSubmitResponse submitCpm(CpmSubmitRequest req) {
            callLog.add("SCHEME:CPM:DECLINE");
            throw new SchemeDeclinedException("ZP_CPM_ERR_001", "CPM declined");
        }
    };

    @BeforeEach
    void setUp() {
        callLog.clear();
    }

    // -----------------------------------------------------------------------
    // CPM LOCAL happy path
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("CPM LOCAL: no prefunding deduction, scheme CPM submitted, APPROVED")
    void cpm_local_happyPath() {
        PaymentOrchestrator orchestrator = new PaymentOrchestrator(
                fakeRate, fakePrefunding, fakeQr, fakeScheme, fakeTxn);

        PaymentOrchestrator.CpmPaymentCommand cmd = new PaymentOrchestrator.CpmPaymentCommand(
                1L, "PTNR-CPM-001", "zeropay",
                "CPM-TOKEN-AABB1122", "M001",
                new BigDecimal("50000"), "KRW",
                new BigDecimal("50000"), "KRW",
                null  // no USD prefunding for LOCAL
        );

        PaymentOrchestrator.PaymentResult result = orchestrator.executeCpm(cmd, PartnerType.LOCAL);

        assertEquals(PaymentStatus.APPROVED, result.status());
        assertEquals("pay_cpm_001", result.paymentId());
        assertEquals("ZP_CPM_TXN_001", result.schemeTxnId());
        assertNull(result.prefundDeductedUsd(), "LOCAL CPM must NOT deduct prefunding");

        // Call order: TXN:CREATE → SCHEME:CPM → TXN:COMMIT:APPROVED
        // No RATE, no QR, no PREFUND
        assertEquals(3, callLog.size(), "Expected exactly 3 calls: TXN:CREATE, SCHEME:CPM, TXN:COMMIT");
        assertEquals("TXN:CREATE:mode=CPM", callLog.get(0));
        assertEquals("SCHEME:CPM:token=CPM-TOKEN-AABB1122", callLog.get(1));
        assertEquals("TXN:COMMIT:APPROVED", callLog.get(2));
    }

    @Test
    @DisplayName("CPM LOCAL: result carries correct scheme fields")
    void cpm_local_resultFieldsCorrect() {
        PaymentOrchestrator orchestrator = new PaymentOrchestrator(
                fakeRate, fakePrefunding, fakeQr, fakeScheme, fakeTxn);

        PaymentOrchestrator.CpmPaymentCommand cmd = new PaymentOrchestrator.CpmPaymentCommand(
                1L, "PTNR-CPM-002", "zeropay",
                "CPM-TOKEN-CCDD2233", "MERCHANT_X",
                new BigDecimal("75000"), "KRW",
                new BigDecimal("75000"), "KRW",
                null
        );

        PaymentOrchestrator.PaymentResult result = orchestrator.executeCpm(cmd, PartnerType.LOCAL);

        assertNotNull(result.approvedAt());
        assertEquals("ZP_CPM_TXN_001", result.schemeTxnId());
        assertEquals("KRW", result.payoutCurrency());
        assertEquals(new BigDecimal("75000"), result.targetPayout());
    }

    // -----------------------------------------------------------------------
    // CPM OVERSEAS — prefunding deducted before scheme call
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("CPM OVERSEAS: prefunding deducted, CPM submitted, APPROVED with deductedUsd")
    void cpm_overseas_prefundingDeducted() {
        PaymentOrchestrator orchestrator = new PaymentOrchestrator(
                fakeRate, fakePrefunding, fakeQr, fakeScheme, fakeTxn);

        PaymentOrchestrator.CpmPaymentCommand cmd = new PaymentOrchestrator.CpmPaymentCommand(
                2L, "PTNR-CPM-OVERSEAS-001", "zeropay",
                "CPM-TOKEN-INTL-001", "MERCHANT_INTL",
                new BigDecimal("50000"), "KRW",
                new BigDecimal("50000"), "KRW",
                new BigDecimal("37.04")  // USD equivalent
        );

        PaymentOrchestrator.PaymentResult result = orchestrator.executeCpm(cmd, PartnerType.OVERSEAS);

        assertEquals(PaymentStatus.APPROVED, result.status());
        assertNotNull(result.prefundDeductedUsd(), "OVERSEAS CPM must deduct prefunding");

        // Call order: TXN:CREATE → PREFUND:DEDUCT → SCHEME:CPM → TXN:COMMIT
        int txnCreate   = callLog.indexOf("TXN:CREATE:mode=CPM");
        int prefund     = callLog.indexOf("PREFUND:DEDUCT");
        int schemeCpm   = callLog.stream().anyMatch(s -> s.startsWith("SCHEME:CPM:token="))
                ? callLog.stream().filter(s -> s.startsWith("SCHEME:CPM:token=")).mapToInt(callLog::indexOf).findFirst().orElse(-1)
                : -1;
        int txnCommit   = callLog.indexOf("TXN:COMMIT:APPROVED");

        // Verify order: create < prefund < cpm < commit
        assert txnCreate < prefund : "TXN:CREATE must precede PREFUND:DEDUCT";
        assert prefund < schemeCpm : "PREFUND:DEDUCT must precede SCHEME:CPM";
        assert schemeCpm < txnCommit : "SCHEME:CPM must precede TXN:COMMIT";
    }

    // -----------------------------------------------------------------------
    // CPM scheme decline — prefunding reversed, FAILED committed
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("CPM OVERSEAS scheme decline: prefunding reversed, FAILED committed, exception rethrown")
    void cpm_overseas_schemeDecline_reversesAndRethrows() {
        PaymentOrchestrator orchestrator = new PaymentOrchestrator(
                fakeRate, fakePrefunding, fakeQr, decliningScheme, fakeTxn);

        PaymentOrchestrator.CpmPaymentCommand cmd = new PaymentOrchestrator.CpmPaymentCommand(
                2L, "PTNR-CPM-DECLINE-001", "zeropay",
                "CPM-TOKEN-DECLINE", "M001",
                new BigDecimal("50000"), "KRW",
                new BigDecimal("50000"), "KRW",
                new BigDecimal("37.04")
        );

        assertThrows(SchemeDeclinedException.class,
                () -> orchestrator.executeCpm(cmd, PartnerType.OVERSEAS));

        // Must have reversed prefunding and committed FAILED
        assertEquals(1, callLog.stream().filter("PREFUND:REVERSE"::equals).count());
        assertEquals(1, callLog.stream().filter("TXN:COMMIT:FAILED"::equals).count());
    }

    @Test
    @DisplayName("CPM LOCAL scheme decline: no prefunding reversal, FAILED committed")
    void cpm_local_schemeDecline_noReversal() {
        PaymentOrchestrator orchestrator = new PaymentOrchestrator(
                fakeRate, fakePrefunding, fakeQr, decliningScheme, fakeTxn);

        PaymentOrchestrator.CpmPaymentCommand cmd = new PaymentOrchestrator.CpmPaymentCommand(
                1L, "PTNR-CPM-DECLINE-002", "zeropay",
                "CPM-TOKEN-DECLINE", "M001",
                new BigDecimal("50000"), "KRW",
                new BigDecimal("50000"), "KRW",
                null
        );

        assertThrows(SchemeDeclinedException.class,
                () -> orchestrator.executeCpm(cmd, PartnerType.LOCAL));

        // No PREFUND:REVERSE for LOCAL
        assertEquals(0, callLog.stream().filter("PREFUND:REVERSE"::equals).count());
        assertEquals(1, callLog.stream().filter("TXN:COMMIT:FAILED"::equals).count());
    }
}
