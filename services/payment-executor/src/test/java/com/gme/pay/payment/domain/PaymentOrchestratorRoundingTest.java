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
            public void reverse(long partnerId, String txnRef) {
                callLog.add("PREFUND:REVERSE");
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

        // ---- fake 7: recording revenue-ledger client ----
        AtomicReference<String> postedRef = new AtomicReference<>();
        AtomicReference<BigDecimal> postedResidual = new AtomicReference<>();
        AtomicReference<String> postedCurrency = new AtomicReference<>();
        RevenueLedgerClient recordingLedger = (reference, residual, currency) -> {
            callLog.add("LEDGER:POST_RESIDUAL");
            postedRef.set(reference);
            postedResidual.set(residual);
            postedCurrency.set(currency);
        };

        // ---- orchestrator under test ----
        PaymentOrchestrator orchestrator = new PaymentOrchestrator(
                fakeRate, fakePrefunding, fakeQr, fakeScheme, fakeTxn,
                bookingService, recordingLedger);

        PaymentOrchestrator.MpmPaymentCommand cmd = new PaymentOrchestrator.MpmPaymentCommand(
                42L, "qte_001", "ZPQR123", "zeropay", "inbound",
                "cust-1", "PTNR_TXN_001");

        // ---- act ----
        PaymentOrchestrator.PaymentResult result = orchestrator.executeMpm(cmd, PartnerType.OVERSEAS);

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
    }
}
