package com.gme.pay.reporting.hometax;

import com.gme.pay.contracts.VatTreatment;
import com.gme.pay.reporting.domain.CommittedTransaction;
import com.gme.pay.reporting.domain.TransactionDirection;
import com.gme.pay.reporting.service.TransactionClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that GME revenue items (2 % FX spread + KRW 500/txn) are excluded
 * from the invoiceable fee base before fee and VAT are computed.
 *
 * <p>Also verifies that non-OUTBOUND-KRW transactions (INBOUND, DOMESTIC, HUB,
 * non-KRW OUTBOUND) are excluded from the aggregation.
 */
class HometaxFeeExclusionTest {

    /** KRW 10,200 gross: after 2 % spread removal → 10,200 / 1.02 = 10,000.00; minus KRW 500 = 9,500. */
    private static final CommittedTransaction OUTBOUND_KRW_TXN = txn(
            1L, TransactionDirection.OUTBOUND, "KRW", new BigDecimal("10200"), false);

    /** INBOUND — must be excluded (not an overseas-merchant KRW payment). */
    private static final CommittedTransaction INBOUND_TXN = txn(
            2L, TransactionDirection.INBOUND, "KRW", new BigDecimal("5000"), false);

    /** DOMESTIC — must be excluded. */
    private static final CommittedTransaction DOMESTIC_TXN = txn(
            3L, TransactionDirection.DOMESTIC, "KRW", new BigDecimal("3000"), true);

    /** OUTBOUND USD — must be excluded (not KRW collection). */
    private static final CommittedTransaction OUTBOUND_USD_TXN = txn(
            4L, TransactionDirection.OUTBOUND, "USD", new BigDecimal("100"), false);

    // =========================================================================
    // TEST 1: FX spread + per-txn levy are excluded from fee base
    // =========================================================================

    @Test
    @DisplayName("GME 2% FX spread and KRW 500 per-txn levy are excluded before fee calculation")
    void fxSpreadAndPerTxnLevyExcluded() {
        // Only OUTBOUND_KRW_TXN should count.
        // Gross = 10,200 KRW
        // After spread: 10,200 / 1.02 = 10,000 KRW
        // After per-txn levy: 10,000 - 500 = 9,500 KRW (the invoiceable base)
        // Fee rate 10 % → fee = 950 KRW
        // ZERO_RATED_EXPORT → VAT = 0; invoice = 950 KRW
        TransactionClient stubClient = (from, to, pid) ->
                List.of(OUTBOUND_KRW_TXN, INBOUND_TXN, DOMESTIC_TXN, OUTBOUND_USD_TXN);

        RegulatoryConfigClient stubReg = partnerCode -> new com.gme.pay.contracts.PartnerRegulatoryConfigView(
                1L, null, null, null, "cert-1",
                VatTreatment.ZERO_RATED_EXPORT,
                null, new BigDecimal("10000000"), List.of(), null, null, null, new BigDecimal("1000000"));

        HometaxInvoiceService service = new HometaxInvoiceService(
                stubClient, stubReg, new StubHometaxClient());

        BigDecimal feeRate = new BigDecimal("0.10"); // 10 % for easy arithmetic
        MerchantInvoiceSummary summary = service.aggregateForMerchant(
                YearMonth.of(2026, 5), feeRate, "TESTMERCHANT");

        // Invoiceable base = 9,500 KRW; fee = 950 KRW
        assertEquals(new BigDecimal("9500"), summary.getTotalKrw(),
                "Invoiceable KRW base must exclude GME 2% spread and KRW 500/txn levy");
        assertEquals(new BigDecimal("950"), summary.getFeeAmount(),
                "Fee = 9,500 × 10 % = 950 KRW");
        assertEquals(0, BigDecimal.ZERO.compareTo(summary.getVatAmount()),
                "ZERO_RATED_EXPORT VAT must be 0");
        assertEquals(new BigDecimal("950"), summary.getInvoiceAmount(),
                "Invoice = fee (no VAT for ZERO_RATED_EXPORT)");
    }

    // =========================================================================
    // TEST 2: Non-OUTBOUND-KRW transactions are excluded
    // =========================================================================

    @Test
    @DisplayName("INBOUND, DOMESTIC, OUTBOUND-USD transactions produce zero invoice")
    void nonOutboundKrw_producesZeroInvoice() {
        TransactionClient stubClient = (from, to, pid) ->
                List.of(INBOUND_TXN, DOMESTIC_TXN, OUTBOUND_USD_TXN);

        RegulatoryConfigClient stubReg = partnerCode -> new com.gme.pay.contracts.PartnerRegulatoryConfigView(
                1L, null, null, null, "cert-1",
                VatTreatment.STANDARD,
                null, new BigDecimal("10000000"), List.of(), null, null, null, new BigDecimal("1000000"));

        HometaxInvoiceService service = new HometaxInvoiceService(
                stubClient, stubReg, new StubHometaxClient());

        MerchantInvoiceSummary summary = service.aggregateForMerchant(
                YearMonth.of(2026, 5), new BigDecimal("0.015"), "TESTMERCHANT");

        assertEquals(0, BigDecimal.ZERO.compareTo(summary.getTotalKrw()),
                "Non-OUTBOUND-KRW transactions must produce 0 KRW base");
        assertEquals(0, BigDecimal.ZERO.compareTo(summary.getFeeAmount()));
        assertEquals(0, BigDecimal.ZERO.compareTo(summary.getVatAmount()));
        assertEquals(0, BigDecimal.ZERO.compareTo(summary.getInvoiceAmount()));
    }

    // =========================================================================
    // TEST 3: STANDARD VAT applied when vat_treatment = STANDARD
    // =========================================================================

    @Test
    @DisplayName("STANDARD vat_treatment applies 10% VAT to fee amount")
    void standardVatTreatment_vatApplied() {
        // OUTBOUND_KRW_TXN: invoiceable base = 9,500; fee rate 10 % → fee = 950
        // STANDARD VAT: 950 × 10 % = 95 KRW; invoice = 1,045 KRW
        TransactionClient stubClient = (from, to, pid) -> List.of(OUTBOUND_KRW_TXN);

        RegulatoryConfigClient stubReg = partnerCode -> new com.gme.pay.contracts.PartnerRegulatoryConfigView(
                2L, null, null, null, "cert-2",
                VatTreatment.STANDARD,
                null, new BigDecimal("10000000"), List.of(), null, null, null, new BigDecimal("1000000"));

        HometaxInvoiceService service = new HometaxInvoiceService(
                stubClient, stubReg, new StubHometaxClient());

        MerchantInvoiceSummary summary = service.aggregateForMerchant(
                YearMonth.of(2026, 5), new BigDecimal("0.10"), "TESTMERCHANT");

        assertEquals(new BigDecimal("950"), summary.getFeeAmount());
        assertEquals(new BigDecimal("95"), summary.getVatAmount(),
                "STANDARD VAT = 950 × 10 % = 95 KRW");
        assertEquals(new BigDecimal("1045"), summary.getInvoiceAmount(),
                "Invoice = 950 + 95 = 1,045 KRW");
    }

    // =========================================================================
    // TEST 4: EXEMPT vat_treatment applies no VAT
    // =========================================================================

    @Test
    @DisplayName("EXEMPT vat_treatment: invoice = fee, VAT = 0")
    void exemptVatTreatment_noVatApplied() {
        TransactionClient stubClient = (from, to, pid) -> List.of(OUTBOUND_KRW_TXN);

        RegulatoryConfigClient stubReg = partnerCode -> new com.gme.pay.contracts.PartnerRegulatoryConfigView(
                3L, null, null, null, "cert-3",
                VatTreatment.EXEMPT,
                null, new BigDecimal("10000000"), List.of(), null, null, null, new BigDecimal("1000000"));

        HometaxInvoiceService service = new HometaxInvoiceService(
                stubClient, stubReg, new StubHometaxClient());

        MerchantInvoiceSummary summary = service.aggregateForMerchant(
                YearMonth.of(2026, 5), new BigDecimal("0.10"), "TESTMERCHANT");

        assertEquals(0, BigDecimal.ZERO.compareTo(summary.getVatAmount()),
                "EXEMPT must produce VAT = 0");
        assertEquals(summary.getFeeAmount(), summary.getInvoiceAmount(),
                "EXEMPT: invoice = fee, no VAT added");
        assertEquals(VatTreatment.EXEMPT, summary.getVatTreatment());
    }

    // =========================================================================
    // Helper
    // =========================================================================

    private static CommittedTransaction txn(
            long id, TransactionDirection dir, String ccy, BigDecimal amount, boolean sameCcy) {
        return new CommittedTransaction(
                id, "REF-" + id, dir, sameCcy,
                null, null,
                amount, ccy,
                amount, ccy,
                null,
                Instant.parse("2026-05-15T10:00:00Z"),
                99L);
    }
}
