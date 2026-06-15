package com.gme.pay.reporting.hometax;

import com.gme.pay.contracts.VatTreatment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the static VAT calculation logic in
 * {@link HometaxInvoiceService#computeVat}.
 *
 * <p>No Spring context, no mocks — pure arithmetic.
 *
 * <p>Rules:
 * <ul>
 *   <li>STANDARD      — VAT = fee × 10 %, rounded to KRW (HALF_UP, scale 0).</li>
 *   <li>ZERO_RATED_EXPORT — VAT = 0 (영세율).</li>
 *   <li>EXEMPT        — VAT = 0 (면세).</li>
 * </ul>
 */
class HometaxVatMathTest {

    // =========================================================================
    // STANDARD — 10 % VAT
    // =========================================================================

    @Test
    @DisplayName("STANDARD: VAT = fee × 10 %, exact KRW amount")
    void standard_vatIs10Percent_exact() {
        BigDecimal fee = new BigDecimal("100000");
        BigDecimal vat = HometaxInvoiceService.computeVat(fee, VatTreatment.STANDARD);
        assertEquals(new BigDecimal("10000"), vat, "10 % of 100,000 KRW = 10,000");
    }

    @Test
    @DisplayName("STANDARD: VAT rounds HALF_UP at scale 0 (KRW no minor units)")
    void standard_vatRoundsHalfUpScaleZero() {
        // fee = 100001; 10 % = 10000.1 → rounds to 10000
        BigDecimal fee = new BigDecimal("100001");
        BigDecimal vat = HometaxInvoiceService.computeVat(fee, VatTreatment.STANDARD);
        assertEquals(new BigDecimal("10000"), vat,
                "10 % of 100,001 = 10000.1 rounds HALF_UP to 10000 at KRW scale 0");
    }

    @Test
    @DisplayName("STANDARD: invoice amount = fee + VAT (10 %)")
    void standard_invoiceAmountFeesPlusVat() {
        BigDecimal fee = new BigDecimal("500000");
        BigDecimal vat = HometaxInvoiceService.computeVat(fee, VatTreatment.STANDARD);
        BigDecimal invoice = fee.add(vat);
        assertEquals(new BigDecimal("550000"), invoice,
                "500,000 fee + 50,000 VAT = 550,000 total invoice");
    }

    // =========================================================================
    // ZERO_RATED_EXPORT — 영세율
    // =========================================================================

    @Test
    @DisplayName("ZERO_RATED_EXPORT: VAT = 0, invoice = fee only")
    void zeroRatedExport_vatIsZero() {
        BigDecimal fee = new BigDecimal("200000");
        BigDecimal vat = HometaxInvoiceService.computeVat(fee, VatTreatment.ZERO_RATED_EXPORT);
        assertEquals(0, BigDecimal.ZERO.compareTo(vat),
                "ZERO_RATED_EXPORT must produce VAT = 0");
        // invoice = fee + 0 = fee
        assertEquals(0, fee.compareTo(fee.add(vat)),
                "Invoice for ZERO_RATED_EXPORT = fee only");
    }

    // =========================================================================
    // EXEMPT — 면세
    // =========================================================================

    @Test
    @DisplayName("EXEMPT: VAT = 0, invoice = fee only")
    void exempt_vatIsZero() {
        BigDecimal fee = new BigDecimal("150000");
        BigDecimal vat = HometaxInvoiceService.computeVat(fee, VatTreatment.EXEMPT);
        assertEquals(0, BigDecimal.ZERO.compareTo(vat),
                "EXEMPT must produce VAT = 0");
    }

    // =========================================================================
    // Edge: zero fee
    // =========================================================================

    @Test
    @DisplayName("STANDARD: zero fee produces zero VAT")
    void standard_zeroFee_zeroVat() {
        BigDecimal vat = HometaxInvoiceService.computeVat(BigDecimal.ZERO, VatTreatment.STANDARD);
        assertEquals(0, BigDecimal.ZERO.compareTo(vat));
    }

    // =========================================================================
    // Null guard
    // =========================================================================

    @Test
    @DisplayName("computeVat null fee throws NullPointerException")
    void computeVat_nullFee_throws() {
        assertThrows(NullPointerException.class,
                () -> HometaxInvoiceService.computeVat(null, VatTreatment.STANDARD));
    }

    @Test
    @DisplayName("computeVat null treatment throws NullPointerException")
    void computeVat_nullTreatment_throws() {
        assertThrows(NullPointerException.class,
                () -> HometaxInvoiceService.computeVat(new BigDecimal("1000"), null));
    }
}
