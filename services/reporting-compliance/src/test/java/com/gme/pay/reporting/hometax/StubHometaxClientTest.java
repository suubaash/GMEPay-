package com.gme.pay.reporting.hometax;

import com.gme.pay.reporting.channel.FilingChannelRegistry;
import com.gme.pay.reporting.persistence.ReportFiling;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.YearMonth;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link StubHometaxClient} — the no-channel Hometax client (GAP T5-2).
 *
 * <p>This client previously fabricated an NTS acknowledgement (status {@code "ACCEPTED"},
 * a fake 24-char confirmation number and a {@code STUB-INV-} invoice id). These tests pin
 * the opposite: with no NTS channel configured, no invoice can be reported as filed and no
 * NTS-issued identifier may be invented.
 */
class StubHometaxClientTest {

    private final StubHometaxClient client = new StubHometaxClient();

    private static HometaxInvoiceRequest request() {
        return new HometaxInvoiceRequest(
                "stub-cert-id",
                42L,
                YearMonth.of(2026, 5),
                new BigDecimal("100000"),
                BigDecimal.ZERO,
                new BigDecimal("100000"),
                "ZERO_RATED_EXPORT");
    }

    @Test
    @DisplayName("status is NOT_FILED_CHANNEL_UNAVAILABLE — never ACCEPTED")
    void submitInvoice_statusIsNotFiled() {
        HometaxInvoiceResponse response = client.submitInvoice(request());

        assertNotNull(response);
        assertEquals(ReportFiling.Status.NOT_FILED_CHANNEL_UNAVAILABLE.name(), response.getStatus());
        assertNotEquals("ACCEPTED", response.getStatus(),
                "a stubbed lane must never report an accepted NTS filing");
        assertFalse(response.isFiled(), "nothing was filed");
    }

    @Test
    @DisplayName("no NTS-issued identifiers are fabricated (invoiceId + confirmation are null)")
    void submitInvoice_fabricatesNoIdentifiers() {
        HometaxInvoiceResponse response = client.submitInvoice(request());

        assertNull(response.getInvoiceId(),
                "invoiceId may only hold a value issued by NTS");
        assertNull(response.getNtsConfirmation(),
                "ntsConfirmation may only hold a value issued by NTS");
    }

    @Test
    @DisplayName("the response explains WHY nothing was filed (missing mTLS cert config)")
    void submitInvoice_surfacesChannelReason() {
        HometaxInvoiceResponse response = client.submitInvoice(request());

        String reason = response.getChannelUnavailableReason();
        assertNotNull(reason, "an unfiled invoice must carry the reason");
        assertTrue(reason.contains("cert-id") || reason.contains("base-url"),
                "the reason must name the missing configuration: " + reason);
    }

    @Test
    @DisplayName("every call is identically unfiled (no counter-based fake sequence)")
    void submitInvoice_repeatedCallsAllUnfiled() {
        HometaxInvoiceResponse r1 = client.submitInvoice(request());
        HometaxInvoiceResponse r2 = client.submitInvoice(request());

        assertEquals(r1.getStatus(), r2.getStatus());
        assertNull(r1.getInvoiceId());
        assertNull(r2.getInvoiceId());
    }

    @Test
    @DisplayName("if a real Hometax channel is configured this client refuses to stand in for it")
    void submitInvoice_failsLoudlyWhenChannelConfigured() {
        StubHometaxClient wired = new StubHometaxClient(new FilingChannelRegistry(
                "", "", "https://api.hometax.go.kr", "vault-doc-real-cert"));

        assertThrows(IllegalStateException.class, () -> wired.submitInvoice(request()),
                "a configured channel with no production client must fail, not silently not-file");
    }
}
