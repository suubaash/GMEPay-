package com.gme.pay.reporting.hometax;

import com.gme.pay.contracts.PartnerRegulatoryConfigView;
import com.gme.pay.contracts.VatTreatment;
import com.gme.pay.reporting.domain.CommittedTransaction;
import com.gme.pay.reporting.domain.TransactionDirection;
import com.gme.pay.reporting.service.TransactionClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link HometaxInvoiceScheduler}.
 *
 * <p>Directly calls the public constructor with concrete values (the {@code @Value}
 * annotations are Spring metadata and do not prevent direct construction in tests).
 * No Spring context, no Mockito. Verifies that:
 * <ul>
 *   <li>When {@code enabled=false} the scheduler runs without error and submits nothing.</li>
 *   <li>When {@code enabled=true} the scheduler aggregates correctly (via
 *       {@link HometaxInvoiceService}) and submits to the stub client.</li>
 * </ul>
 *
 * <p>The {@link TransactionClient} is mocked with a hand-rolled lambda stub —
 * no dependency on Lane A's unfinished classes.
 */
class HometaxInvoiceSchedulerTest {

    /** KRW 102,000 gross OUTBOUND; invoiceable base = 102,000/1.02 - 500 = 99,500 */
    private static final CommittedTransaction OUTBOUND_TXN = new CommittedTransaction(
            10L, "REF-10",
            TransactionDirection.OUTBOUND, false,
            null, null,
            new BigDecimal("102000"), "KRW",
            new BigDecimal("102000"), "KRW",
            null,
            Instant.parse("2026-05-15T10:00:00Z"),
            55L);

    // =========================================================================
    // TEST 1: Scheduler gate (disabled) — no submit called
    // =========================================================================

    @Test
    @DisplayName("Scheduler with enabled=false runs without error and does not call Hometax")
    void disabled_schedulerRunsWithoutError() {
        TrackingHometaxClient trackingClient = new TrackingHometaxClient();

        TransactionClient txnClient = (from, to, pid) -> List.of(OUTBOUND_TXN);
        RegulatoryConfigClient regClient = code -> standardConfig();
        HometaxInvoiceService service = new HometaxInvoiceService(txnClient, regClient, trackingClient);

        HometaxInvoiceScheduler scheduler = new HometaxInvoiceScheduler(
                service,
                false,   // enabled = false
                "TESTMERCHANT",
                new BigDecimal("0.015"),
                "cert-id-stub");

        // Must not throw
        assertDoesNotThrow(scheduler::runMonthlyInvoice,
                "Disabled scheduler must not throw");
        assertEquals(0, trackingClient.callCount,
                "When disabled, Hometax must not be called");
    }

    // =========================================================================
    // TEST 2: Scheduler gate (enabled) — aggregates + submits
    // =========================================================================

    @Test
    @DisplayName("Scheduler with enabled=true aggregates transactions and submits to Hometax")
    void enabled_schedulerAggregatesAndSubmits() {
        TrackingHometaxClient trackingClient = new TrackingHometaxClient();

        TransactionClient txnClient = (from, to, pid) -> List.of(OUTBOUND_TXN);
        RegulatoryConfigClient regClient = code -> standardConfig();
        HometaxInvoiceService service = new HometaxInvoiceService(txnClient, regClient, trackingClient);

        HometaxInvoiceScheduler scheduler = new HometaxInvoiceScheduler(
                service,
                true,   // enabled = true
                "TESTMERCHANT",
                new BigDecimal("0.015"),
                "cert-id-stub");

        assertDoesNotThrow(scheduler::runMonthlyInvoice);
        assertEquals(1, trackingClient.callCount,
                "Enabled scheduler must submit exactly one invoice per run");

        // The request must carry the correct cert id
        assertEquals("cert-id-stub", trackingClient.lastRequest.getIssuerCertId());
        // The billing period must be a non-null YYYY-MM string
        assertNotNull(trackingClient.lastRequest.getBillingPeriod());
    }

    // =========================================================================
    // TEST 3: Scheduler aggregates KRW total correctly
    // =========================================================================

    @Test
    @DisplayName("Scheduler aggregates correct KRW fee base (excludes GME spread + per-txn levy)")
    void enabled_aggregatesCorrectKrwFeeBase() {
        TrackingHometaxClient trackingClient = new TrackingHometaxClient();

        // One KRW 102,000 OUTBOUND; invoiceable = 102,000/1.02 - 500 = 99,500 KRW
        // fee rate 1.5 % → fee = 99,500 × 0.015 = 1,493 KRW (rounded HALF_UP)
        // ZERO_RATED_EXPORT → VAT = 0; invoice = 1,493 KRW
        TransactionClient txnClient = (from, to, pid) -> List.of(OUTBOUND_TXN);
        RegulatoryConfigClient regClient = code -> zeroRatedConfig();
        HometaxInvoiceService service = new HometaxInvoiceService(txnClient, regClient, trackingClient);

        HometaxInvoiceScheduler scheduler = new HometaxInvoiceScheduler(
                service, true, "TESTMERCHANT", new BigDecimal("0.015"), "cert");

        scheduler.runMonthlyInvoice();

        HometaxInvoiceRequest req = trackingClient.lastRequest;
        // supply_amount = 1,493; vat_amount = 0; total_amount = 1,493
        assertEquals("1493", req.getSupplyAmount(),
                "supply_amount must be 99,500 × 1.5 % = 1,493 KRW (rounded HALF_UP)");
        assertEquals("0", req.getVatAmount(), "ZERO_RATED_EXPORT VAT must be 0");
        assertEquals("1493", req.getTotalAmount());
    }

    // =========================================================================
    // TEST 4: Empty transaction list → zero invoice still submitted
    // =========================================================================

    @Test
    @DisplayName("Zero transactions: scheduler submits a zero-value invoice without error")
    void enabled_noTransactions_submitsZeroInvoice() {
        TrackingHometaxClient trackingClient = new TrackingHometaxClient();

        TransactionClient txnClient = (from, to, pid) -> List.of();
        RegulatoryConfigClient regClient = code -> zeroRatedConfig();
        HometaxInvoiceService service = new HometaxInvoiceService(txnClient, regClient, trackingClient);

        HometaxInvoiceScheduler scheduler = new HometaxInvoiceScheduler(
                service, true, "TESTMERCHANT", new BigDecimal("0.015"), "cert");

        assertDoesNotThrow(scheduler::runMonthlyInvoice);
        assertEquals(1, trackingClient.callCount,
                "Even with 0 transactions a submission is made (zero-value invoice)");
        assertEquals("0", trackingClient.lastRequest.getSupplyAmount());
        assertEquals("0", trackingClient.lastRequest.getTotalAmount());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static PartnerRegulatoryConfigView standardConfig() {
        return new PartnerRegulatoryConfigView(
                55L, null, null, null, "cert-id-stub",
                VatTreatment.STANDARD,
                null, new BigDecimal("10000000"), List.of(), null, null, null, new BigDecimal("1000000"));
    }

    private static PartnerRegulatoryConfigView zeroRatedConfig() {
        return new PartnerRegulatoryConfigView(
                55L, null, null, null, "cert",
                VatTreatment.ZERO_RATED_EXPORT,
                null, new BigDecimal("10000000"), List.of(), null, null, null, new BigDecimal("1000000"));
    }

    /**
     * Simple tracking stub: counts calls and captures the last request.
     * Not a Mockito mock — plain Java, no extra deps.
     */
    static class TrackingHometaxClient implements HometaxClient {
        int callCount = 0;
        HometaxInvoiceRequest lastRequest;

        @Override
        public HometaxInvoiceResponse submitInvoice(HometaxInvoiceRequest request) {
            callCount++;
            lastRequest = request;
            return new HometaxInvoiceResponse("STUB-INV-TEST-" + callCount, "NTS-CONFIRM-" + callCount, "ACCEPTED");
        }
    }
}
