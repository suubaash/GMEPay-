package com.gme.pay.payment.domain;

import com.gme.pay.payment.domain.client.PartnerConfigClient;
import com.gme.pay.payment.domain.client.PrefundingClient;
import com.gme.pay.payment.domain.client.QrClient;
import com.gme.pay.payment.domain.client.RateClient;
import com.gme.pay.payment.domain.client.RevenueLedgerClient;
import com.gme.pay.payment.domain.client.SchemeClient;
import com.gme.pay.payment.domain.client.TransactionClient;
import com.gme.pay.payment.domain.settlement.SettlementBookingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Happy-path test verifying that {@link PaymentOrchestrator}, when wired with a
 * {@link SettlementBookingService} + {@link RevenueLedgerClient}, books the per-partner
 * settlement amount, includes it on the APPROVED {@link TransactionClient.StatusPatch},
 * and posts the residual to revenue-ledger AFTER commit.
 *
 * <p>All collaborators are hand-rolled fakes; no Spring, no mocks.
 */
class PaymentOrchestratorRoundingTest {

    @Test
    @DisplayName("APPROVED commit carries rounding lock and residual posts AFTER commit")
    void roundingLockOnApprovedAndResidualPostedAfterCommit() {

        // ---- ordered call log so we can prove ordering ----
        List<String> callLog = new ArrayList<>();

        // ---- fake 1: rate-fx returns a precise 10500.567 USD collection ----
        BigDecimal preciseCollection = new BigDecimal("10500.567");
        RateClient fakeRate = (quoteId, partnerId) -> {
            callLog.add("RATE");
            return new RateClient.RateQuoteView(
                    quoteId, partnerId, "zeropay", "inbound",
                    new BigDecimal("50000"), "KRW",
                    new BigDecimal("37.015197"), new BigDecimal("35.562589"),
                    new BigDecimal("0.925380"), new BigDecimal("0.370152"),
                    new BigDecimal("37.015197"), new BigDecimal("0.35"),
                    preciseCollection, "USD",
                    new BigDecimal("1.025610"), new BigDecimal("1351.00"),
                    Instant.now().plusSeconds(300), false);
        };

        // ---- fake 2: qr-service ----
        QrClient fakeQr = qr -> {
            callLog.add("QR");
            return QrClient.MerchantView.of("M001", "Test Merchant", "USD", "zeropay");
        };

        // ---- fake 3: transaction-mgmt ----
        AtomicReference<TransactionClient.StatusPatch> capturedApproved = new AtomicReference<>();
        TransactionClient fakeTxn = new TransactionClient() {
            @Override
            public CreateResult createPending(CreateRequest req) {
                callLog.add("TXN:CREATE");
                return new CreateResult("txn_ROUND_001", "pay_ROUND_001", Instant.now());
            }
            @Override
            public void commitStatus(String txnRef, StatusPatch patch) {
                callLog.add("TXN:COMMIT:" + patch.newStatus().name());
                if (patch.newStatus() == PaymentStatus.APPROVED) {
                    capturedApproved.set(patch);
                }
            }
        };

        // ---- fake 4: prefunding (OVERSEAS path) ----
        PrefundingClient fakePrefunding = new PrefundingClient() {
            @Override
            public DeductionResult deduct(long partnerId, String txnRef, BigDecimal amountUsd) {
                callLog.add("PREFUND:DEDUCT");
                return new DeductionResult(amountUsd, new BigDecimal("962.985"));
            }
            @Override
            public ReverseResult reverse(long partnerId, String txnRef) {
                callLog.add("PREFUND:REVERSE");
                return new ReverseResult(BigDecimal.ZERO, BigDecimal.ZERO);
            }
            @Override
            public ReservationResult reserve(long partnerId, String txnRef, BigDecimal amountUsd) {
                callLog.add("PREFUND:RESERVE");
                return new ReservationResult(amountUsd, new BigDecimal("962.985"), new BigDecimal("1000"));
            }
            @Override
            public CaptureResult capture(long partnerId, String txnRef) {
                callLog.add("PREFUND:CAPTURE");
                return new CaptureResult(new BigDecimal("37.015197"), new BigDecimal("962.985"));
            }
        };

        // ---- fake 5: scheme ----
        SchemeClient fakeScheme = new SchemeClient() {
            @Override
            public MpmSubmitResponse submitMpm(MpmSubmitRequest req) {
                callLog.add("SCHEME:SUBMIT");
                return new MpmSubmitResponse("ZP_OK", "ZP_TXN_001", Instant.now());
            }
            @Override
            public void cancelPayment(String schemeTxnRef, String reason) { callLog.add("SCHEME:CANCEL"); }
            @Override
            public CpmSubmitResponse submitCpm(CpmSubmitRequest req) {
                callLog.add("SCHEME:CPM_SUBMIT");
                return new CpmSubmitResponse("ZP_CPM", "ZP_CPM_TXN", Instant.now());
            }
        };

        // ---- fake 6: partner-config returns DOWN @ USD ----
        PartnerConfigClient fakePartnerConfig =
                id -> new PartnerConfigClient.PartnerConfigView(id, "OVERSEAS", "USD", RoundingMode.DOWN);
        SettlementBookingService bookingService = new SettlementBookingService(fakePartnerConfig);

        // ---- fake 7: recording revenue-ledger client (records BOTH residual + revenue capture) ----
        AtomicReference<String> postedRef = new AtomicReference<>();
        AtomicReference<BigDecimal> postedResidual = new AtomicReference<>();
        AtomicReference<String> postedCurrency = new AtomicReference<>();
        AtomicReference<String> capturedTxnRef = new AtomicReference<>();
        AtomicReference<BigDecimal> capturedCollMargin = new AtomicReference<>();
        AtomicReference<BigDecimal> capturedPayMargin = new AtomicReference<>();
        AtomicReference<BigDecimal> capturedSvcCharge = new AtomicReference<>();
        AtomicReference<String> capturedSvcCcy = new AtomicReference<>();
        RevenueLedgerClient recordingLedger = new RevenueLedgerClient() {
            @Override
            public void postRoundingResidual(String reference, BigDecimal residual, String currency) {
                callLog.add("LEDGER:POST_RESIDUAL");
                postedRef.set(reference);
                postedResidual.set(residual);
                postedCurrency.set(currency);
            }
            @Override
            public void postRevenueCapture(String txnRef, long partnerId, long schemeId,
                                           java.time.LocalDate revenueDate,
                                           BigDecimal collectionMarginUsd, BigDecimal payoutMarginUsd,
                                           BigDecimal serviceCharge, String serviceChargeCcy,
                                           BigDecimal feeSharePct) {
                callLog.add("LEDGER:CAPTURE");
                capturedTxnRef.set(txnRef);
                capturedCollMargin.set(collectionMarginUsd);
                capturedPayMargin.set(payoutMarginUsd);
                capturedSvcCharge.set(serviceCharge);
                capturedSvcCcy.set(serviceChargeCcy);
            }
        };

        // ---- orchestrator under test ----
        PaymentOrchestrator orchestrator = new PaymentOrchestrator(
                fakeRate, fakePrefunding, fakeQr, fakeScheme, fakeTxn,
                bookingService, recordingLedger);

        PaymentOrchestrator.MpmPaymentCommand cmd = new PaymentOrchestrator.MpmPaymentCommand(
                42L, "qte_001", "ZPQR123", "zeropay", "inbound",
                "cust-1", "PTNR_TXN_001", "PARTNER_X",
                preciseCollection, "USD");   // matches the fake quote's collection amount/currency

        // ---- act: two-phase (authorize reserves; confirm submits + captures + books) ----
        PaymentOrchestrator.AuthorizeResult auth = orchestrator.authorizeMpm(cmd, PartnerType.OVERSEAS);
        var q2 = auth.quote();
        var m2 = auth.merchant();
        PaymentOrchestrator.ConfirmContext ctx = new PaymentOrchestrator.ConfirmContext(
                cmd.partnerId(), PartnerType.OVERSEAS, cmd.partnerCode(), cmd.partnerTxnRef(),
                auth.txnRef(), auth.paymentId(), cmd.schemeId(), cmd.merchantQr(),
                m2.merchantId(), m2.merchantName(), q2.targetPayout(), q2.payoutCurrency(),
                q2.collectionAmount(), q2.collectionCurrency(), auth.reservedUsd(), q2.offerRateColl(),
                q2.collectionMarginUsd(), q2.payoutMarginUsd(), q2.serviceCharge(),
                cmd.direction(), auth.merchantFeeRate(), auth.createdAt());
        PaymentOrchestrator.PaymentResult result = orchestrator.confirmMpm(ctx);

        // ---- assert: result reached APPROVED ----
        assertEquals(PaymentStatus.APPROVED, result.status());

        // ---- assert: APPROVED StatusPatch carries booked / mode / residual ----
        TransactionClient.StatusPatch patch = capturedApproved.get();
        assertNotNull(patch, "APPROVED StatusPatch must have been captured");
        // precise 10500.567 with DOWN @ 2dp -> booked 10500.56, residual 0.007
        assertEquals(0, patch.bookedSettlementAmount().compareTo(new BigDecimal("10500.56")),
                "booked should be 10500.56");
        assertEquals("DOWN", patch.settlementRoundingMode());
        assertEquals(0, patch.roundingResidual().compareTo(new BigDecimal("0.007")),
                "residual should be 0.007");

        // ---- assert: recording client was called with (txnRef, residual, currency) ----
        assertEquals("txn_ROUND_001", postedRef.get());
        assertEquals(0, postedResidual.get().compareTo(new BigDecimal("0.007")));
        assertEquals("USD", postedCurrency.get());

        // ---- assert: residual posted AFTER commit (ordering) ----
        int commitIdx = callLog.indexOf("TXN:COMMIT:APPROVED");
        int ledgerIdx = callLog.indexOf("LEDGER:POST_RESIDUAL");
        assertTrue(commitIdx >= 0, "expected TXN:COMMIT:APPROVED in log " + callLog);
        assertTrue(ledgerIdx > commitIdx,
                "residual must be posted AFTER commit; log: " + callLog);

        // ---- assert: per-transaction revenue capture posted AFTER commit (P1-4) ----
        assertEquals("txn_ROUND_001", capturedTxnRef.get(), "capture keyed by txnRef");
        assertEquals(0, capturedCollMargin.get().compareTo(new BigDecimal("0.925380")),
                "collection margin from the quote");
        assertEquals(0, capturedPayMargin.get().compareTo(new BigDecimal("0.370152")),
                "payout margin from the quote");
        assertEquals(0, capturedSvcCharge.get().compareTo(new BigDecimal("0.35")),
                "service charge from the quote");
        assertEquals("USD", capturedSvcCcy.get());
        int captureIdx = callLog.indexOf("LEDGER:CAPTURE");
        assertTrue(captureIdx > commitIdx, "revenue capture must be posted AFTER commit; log: " + callLog);
    }
}
