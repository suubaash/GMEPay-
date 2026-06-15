package com.gme.pay.reporting.hometax;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.YearMonth;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link StubHometaxClient} — verifies the stub returns a non-null
 * invoiceId and NTS confirmation without making any real network call.
 */
class StubHometaxClientTest {

    private final StubHometaxClient client = new StubHometaxClient();

    @Test
    @DisplayName("submitInvoice returns non-null invoiceId")
    void submitInvoice_returnsNonNullInvoiceId() {
        HometaxInvoiceRequest req = new HometaxInvoiceRequest(
                "stub-cert-id",
                42L,
                YearMonth.of(2026, 5),
                new BigDecimal("100000"),
                BigDecimal.ZERO,
                new BigDecimal("100000"),
                "ZERO_RATED_EXPORT");

        HometaxInvoiceResponse response = client.submitInvoice(req);

        assertNotNull(response, "Response must not be null");
        assertNotNull(response.getInvoiceId(), "invoiceId must not be null");
        assertFalse(response.getInvoiceId().isBlank(), "invoiceId must not be blank");
    }

    @Test
    @DisplayName("submitInvoice returns non-null NTS confirmation")
    void submitInvoice_returnsNtsConfirmation() {
        HometaxInvoiceRequest req = new HometaxInvoiceRequest(
                "stub-cert-id",
                42L,
                YearMonth.of(2026, 5),
                new BigDecimal("50000"),
                new BigDecimal("5000"),
                new BigDecimal("55000"),
                "STANDARD");

        HometaxInvoiceResponse response = client.submitInvoice(req);

        assertNotNull(response.getNtsConfirmation(), "NTS confirmation must not be null");
        assertFalse(response.getNtsConfirmation().isBlank(), "NTS confirmation must not be blank");
    }

    @Test
    @DisplayName("submitInvoice status is ACCEPTED")
    void submitInvoice_statusIsAccepted() {
        HometaxInvoiceRequest req = new HometaxInvoiceRequest(
                "cert",
                1L,
                YearMonth.of(2026, 6),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "EXEMPT");

        HometaxInvoiceResponse response = client.submitInvoice(req);
        assertEquals("ACCEPTED", response.getStatus());
    }

    @Test
    @DisplayName("Two successive calls return different invoiceIds (counter increments)")
    void submitInvoice_successiveCalls_differentInvoiceIds() {
        HometaxInvoiceRequest req = new HometaxInvoiceRequest(
                "cert", 1L, YearMonth.of(2026, 6),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "EXEMPT");

        HometaxInvoiceResponse r1 = client.submitInvoice(req);
        HometaxInvoiceResponse r2 = client.submitInvoice(req);

        assertNotEquals(r1.getInvoiceId(), r2.getInvoiceId(),
                "Each stub call must produce a unique invoiceId");
    }
}
