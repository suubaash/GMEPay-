package com.gme.pay.reporting.kofiu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link StubKofiuFeedClient}.
 *
 * <p>Verifies that the stub returns a receipt id with the expected prefix and
 * does not throw on a valid submission.
 */
class StubKofiuFeedClientTest {

    private static final LocalDate REPORT_DATE = LocalDate.of(2026, 6, 15);

    // =========================================================================
    // TEST 1: submit returns a receipt id with STUB- prefix
    // =========================================================================

    @Test
    @DisplayName("Stub client: submit returns a receipt id prefixed with 'STUB-'")
    void submit_returnsStubReceiptId(@TempDir Path tempDir) {
        StubKofiuFeedClient client = new StubKofiuFeedClient();
        Path fakeFeedFile = tempDir.resolve("KOFIU_20260615.dat");
        KofiuReportBatch batch = new KofiuReportBatch(REPORT_DATE, List.of(), List.of());

        String receiptId = client.submit(fakeFeedFile, batch);

        assertNotNull(receiptId, "Receipt id must not be null");
        assertTrue(receiptId.startsWith(StubKofiuFeedClient.STUB_RECEIPT_PREFIX),
                "Stub receipt id must start with '" + StubKofiuFeedClient.STUB_RECEIPT_PREFIX + "'");
    }

    // =========================================================================
    // TEST 2: successive calls return distinct receipt ids
    // =========================================================================

    @Test
    @DisplayName("Stub client: successive submit calls return distinct receipt ids")
    void submit_distinctReceiptIds(@TempDir Path tempDir) {
        StubKofiuFeedClient client = new StubKofiuFeedClient();
        Path fakeFeedFile = tempDir.resolve("KOFIU_20260615.dat");
        KofiuReportBatch batch = new KofiuReportBatch(REPORT_DATE, List.of(), List.of());

        String id1 = client.submit(fakeFeedFile, batch);
        String id2 = client.submit(fakeFeedFile, batch);

        assertNotEquals(id1, id2, "Each stub submit call must return a distinct receipt id");
    }

    // =========================================================================
    // TEST 3: submit with non-empty batch does not throw
    // =========================================================================

    @Test
    @DisplayName("Stub client: submit with CTR + STR records does not throw")
    void submit_withReports_doesNotThrow(@TempDir Path tempDir) {
        StubKofiuFeedClient client = new StubKofiuFeedClient();
        Path fakeFeedFile = tempDir.resolve("KOFIU_20260615.dat");

        CtrReport ctr = new CtrReport(
                "EU-1", 1L, REPORT_DATE, new BigDecimal("10000000"), 1, List.of(1L));
        StrReport str = new StrReport(
                2L, "REF-2", "EU-2", 1L, REPORT_DATE, new BigDecimal("500000"), "KRW", "USD");
        KofiuReportBatch batch = new KofiuReportBatch(REPORT_DATE, List.of(ctr), List.of(str));

        assertDoesNotThrow(() -> client.submit(fakeFeedFile, batch));
    }
}
