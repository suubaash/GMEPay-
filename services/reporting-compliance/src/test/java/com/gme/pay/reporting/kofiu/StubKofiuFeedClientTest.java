package com.gme.pay.reporting.kofiu;

import com.gme.pay.reporting.channel.FilingChannelRegistry;
import com.gme.pay.reporting.channel.FilingTransmissionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link StubKofiuFeedClient} — the no-channel KoFIU client (GAP T5-2).
 *
 * <p>This client used to return a fabricated receipt id {@code "STUB-<uuid>"}, which made a
 * never-transmitted CTR/STR feed look acknowledged. It must now report that nothing was
 * transmitted, and must be structurally unable to attach a receipt id.
 */
class StubKofiuFeedClientTest {

    private static final LocalDate REPORT_DATE = LocalDate.of(2026, 6, 15);

    @Test
    @DisplayName("submit reports NOT transmitted and fabricates no receipt id")
    void submit_reportsNotTransmitted(@TempDir Path tempDir) {
        StubKofiuFeedClient client = new StubKofiuFeedClient();
        Path fakeFeedFile = tempDir.resolve("KOFIU_20260615.dat");
        KofiuReportBatch batch = new KofiuReportBatch(REPORT_DATE, List.of(), List.of());

        FilingTransmissionResult result = client.submit(fakeFeedFile, batch);

        assertFalse(result.transmitted(), "no KoFIU channel exists — nothing can be transmitted");
        assertNull(result.receiptId(), "a receipt id may only come from KoFIU");
        assertNotNull(result.reason(), "the caller must be told why nothing was transmitted");
        assertTrue(result.reason().contains("kofiu.channel.endpoint"),
                "the reason must name the missing config: " + result.reason());
    }

    @Test
    @DisplayName("FilingTransmissionResult cannot be constructed with a receipt id but not transmitted")
    void result_cannotFakeAReceipt() {
        assertThrows(IllegalArgumentException.class,
                () -> new FilingTransmissionResult(false, "STUB-1234", "no channel"),
                "the result type itself must reject a fabricated receipt");
        assertThrows(NullPointerException.class,
                () -> new FilingTransmissionResult(true, null, null),
                "a transmitted filing must carry a real receipt id");
    }

    @Test
    @DisplayName("submit with CTR + STR records does not throw and still reports not transmitted")
    void submit_withReports_doesNotThrow(@TempDir Path tempDir) {
        StubKofiuFeedClient client = new StubKofiuFeedClient();
        Path fakeFeedFile = tempDir.resolve("KOFIU_20260615.dat");

        CtrReport ctr = new CtrReport(
                "EU-1", 1L, REPORT_DATE, new BigDecimal("10000000"), 1, List.of(1L));
        StrReport str = new StrReport(
                2L, "REF-2", "EU-2", 1L, REPORT_DATE, new BigDecimal("500000"), "KRW", "USD");
        KofiuReportBatch batch = new KofiuReportBatch(REPORT_DATE, List.of(ctr), List.of(str));

        FilingTransmissionResult result =
                assertDoesNotThrow(() -> client.submit(fakeFeedFile, batch));
        assertFalse(result.transmitted());
    }

    @Test
    @DisplayName("if a real KoFIU endpoint is configured this client refuses to stand in for it")
    void submit_failsLoudlyWhenChannelConfigured(@TempDir Path tempDir) {
        StubKofiuFeedClient wired = new StubKofiuFeedClient(
                new FilingChannelRegistry("", "sftp://kofiu.example/in", "", ""));
        KofiuReportBatch batch = new KofiuReportBatch(REPORT_DATE, List.of(), List.of());

        assertThrows(IllegalStateException.class,
                () -> wired.submit(tempDir.resolve("KOFIU_20260615.dat"), batch));
    }
}
