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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PaymentOrchestrator#executeCpm} on the two-phase ledger SPINE (Step 9):
 * CPM is synchronous (customer present) but rides the same money-safety ordering as MPM (Step 4) —
 * RESERVE the float (hold, not debit), scheme balance-check gate, submit (the irreversible charge,
 * LAST), then CAPTURE only on success. Decline RELEASES the hold; the legacy deduct-before-submit
 * is gone.
 *
 * <p>No Spring context, no Docker. Hand-written fakes record call order.
 */
class PaymentOrchestratorCpmTest {

    private final List<String> callLog = new ArrayList<>();

    private final RateClient fakeRate = (quoteId, partnerId) -> {
        callLog.add("RATE");
        return null;
    };

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

    /** Two-phase prefunding fake: reserve/capture/release recorded; the legacy deduct must NOT run. */
    private final PrefundingClient fakePrefunding = new PrefundingClient() {
        @Override
        public DeductionResult deduct(long partnerId, String txnRef, BigDecimal amountUsd) {
            throw new AssertionError("deduct must NOT be called on the CPM spine — use reserve/capture");
        }

        @Override
        public ReverseResult reverse(long partnerId, String txnRef) {
            callLog.add("PREFUND:REVERSE");
            return new ReverseResult(new BigDecimal("125.50"), new BigDecimal("900.00"));
        }

        @Override
        public ReservationResult reserve(long partnerId, String txnRef, BigDecimal amountUsd) {
            callLog.add("PREFUND:RESERVE");
            return new ReservationResult(amountUsd, new BigDecimal("962.21"), new BigDecimal("1000.00"));
        }

        @Override
        public CaptureResult capture(long partnerId, String txnRef) {
            callLog.add("PREFUND:CAPTURE");
            return new CaptureResult(new BigDecimal("37.04"), new BigDecimal("962.21"));
        }

        @Override
        public ReleaseResult release(long partnerId, String txnRef) {
            callLog.add("PREFUND:RELEASE");
            return new ReleaseResult(new BigDecimal("37.04"), new BigDecimal("1000.00"));
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

        @Override
        public BalanceCheckResult checkBalance(String schemeId, BigDecimal amount, String currency) {
            callLog.add("SCHEME:BALANCE");
            return new BalanceCheckResult(true, new BigDecimal("1000000000"));
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

        @Override
        public BalanceCheckResult checkBalance(String schemeId, BigDecimal amount, String currency) {
            callLog.add("SCHEME:BALANCE");
            return new BalanceCheckResult(true, new BigDecimal("1000000000"));
        }
    };

    private static PaymentOrchestrator.CpmPaymentCommand cpm(long partnerId, String token,
                                                             BigDecimal collectionUsd) {
        return new PaymentOrchestrator.CpmPaymentCommand(
                partnerId, "PTNR-CPM-" + partnerId, "zeropay",
                token, "M001",
                new BigDecimal("50000"), "KRW",
                new BigDecimal("50000"), "KRW",
                collectionUsd);
    }

    @BeforeEach
    void setUp() {
        callLog.clear();
    }

    @Test
    @DisplayName("CPM LOCAL: no float hold, scheme CPM submitted, APPROVED")
    void cpm_local_happyPath() {
        PaymentOrchestrator orchestrator = new PaymentOrchestrator(
                fakeRate, fakePrefunding, fakeQr, fakeScheme, fakeTxn);

        PaymentOrchestrator.PaymentResult result =
                orchestrator.executeCpm(cpm(1L, "CPM-TOKEN-AABB1122", null), PartnerType.LOCAL);

        assertEquals(PaymentStatus.APPROVED, result.status());
        assertEquals("ZP_CPM_TXN_001", result.schemeTxnId());
        assertNull(result.prefundDeductedUsd(), "LOCAL CPM holds no float");

        // LOCAL: no reserve/capture; the scheme balance gate still runs, then submit, then commit.
        assertEquals("TXN:CREATE:mode=CPM", callLog.get(0));
        assertTrue(callLog.contains("SCHEME:BALANCE"));
        assertEquals("SCHEME:CPM:token=CPM-TOKEN-AABB1122",
                callLog.stream().filter(s -> s.startsWith("SCHEME:CPM")).findFirst().orElse(""));
        assertEquals("TXN:COMMIT:APPROVED", callLog.get(callLog.size() - 1));
        assertTrue(callLog.stream().noneMatch(s -> s.startsWith("PREFUND")), "LOCAL holds no float");
    }

    @Test
    @DisplayName("CPM OVERSEAS: RESERVE → balance → submit → CAPTURE → APPROVED (deduct never called)")
    void cpm_overseas_reservesThenCaptures() {
        PaymentOrchestrator orchestrator = new PaymentOrchestrator(
                fakeRate, fakePrefunding, fakeQr, fakeScheme, fakeTxn);

        PaymentOrchestrator.PaymentResult result =
                orchestrator.executeCpm(cpm(2L, "CPM-TOKEN-INTL-001", new BigDecimal("37.04")),
                        PartnerType.OVERSEAS);

        assertEquals(PaymentStatus.APPROVED, result.status());
        assertNotNull(result.prefundDeductedUsd(), "OVERSEAS CPM captures the held float");

        int reserve = callLog.indexOf("PREFUND:RESERVE");
        int balance = callLog.indexOf("SCHEME:BALANCE");
        int cpmSubmit = callLog.indexOf("SCHEME:CPM:token=CPM-TOKEN-INTL-001");
        int capture = callLog.indexOf("PREFUND:CAPTURE");
        int commit = callLog.indexOf("TXN:COMMIT:APPROVED");
        assertTrue(reserve >= 0 && balance > reserve && cpmSubmit > balance
                        && capture > cpmSubmit && commit > capture,
                "order must be RESERVE → BALANCE → CPM submit → CAPTURE → COMMIT; log=" + callLog);
    }

    @Test
    @DisplayName("CPM OVERSEAS scheme decline: hold RELEASED (not captured), FAILED committed, rethrown")
    void cpm_overseas_schemeDecline_releasesAndRethrows() {
        PaymentOrchestrator orchestrator = new PaymentOrchestrator(
                fakeRate, fakePrefunding, fakeQr, decliningScheme, fakeTxn);

        assertThrows(SchemeDeclinedException.class,
                () -> orchestrator.executeCpm(cpm(2L, "CPM-TOKEN-DECLINE", new BigDecimal("37.04")),
                        PartnerType.OVERSEAS));

        assertEquals(1, callLog.stream().filter("PREFUND:RESERVE"::equals).count());
        assertEquals(1, callLog.stream().filter("PREFUND:RELEASE"::equals).count(), "decline releases the hold");
        assertEquals(0, callLog.stream().filter("PREFUND:CAPTURE"::equals).count(), "no capture on decline");
        assertEquals(1, callLog.stream().filter("TXN:COMMIT:FAILED"::equals).count());
    }

    @Test
    @DisplayName("CPM LOCAL scheme decline: no float, FAILED committed")
    void cpm_local_schemeDecline_noFloat() {
        PaymentOrchestrator orchestrator = new PaymentOrchestrator(
                fakeRate, fakePrefunding, fakeQr, decliningScheme, fakeTxn);

        assertThrows(SchemeDeclinedException.class,
                () -> orchestrator.executeCpm(cpm(1L, "CPM-TOKEN-DECLINE", null), PartnerType.LOCAL));

        assertTrue(callLog.stream().noneMatch(s -> s.startsWith("PREFUND")), "LOCAL holds no float");
        assertEquals(1, callLog.stream().filter("TXN:COMMIT:FAILED"::equals).count());
    }
}
