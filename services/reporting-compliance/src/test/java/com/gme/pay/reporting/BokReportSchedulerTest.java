package com.gme.pay.reporting;

import com.gme.pay.reporting.bok.BokFxFileBuilder;
import com.gme.pay.reporting.bok.BokReportScheduler;
import com.gme.pay.reporting.domain.CommittedTransaction;
import com.gme.pay.reporting.domain.TransactionDirection;
import com.gme.pay.reporting.service.TransactionClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link BokReportScheduler}.
 *
 * <p>Uses hand-rolled stubs — no Spring context, no Mockito.
 *
 * <p>Tests verify:
 * <ul>
 *   <li>When {@code enabled=true}, {@link BokReportScheduler#runForDate} writes FX1014/FX1015
 *       files to the configured outbound directory.</li>
 *   <li>When {@code enabled=false} (default), no files are written.</li>
 *   <li>Domestic/same-currency transactions are filtered before file writing.</li>
 * </ul>
 */
class BokReportSchedulerTest {

    @TempDir
    Path tempDir;

    private static final LocalDate REPORT_DATE = LocalDate.of(2026, 5, 20);

    // INBOUND transaction — should appear in FX1015 file
    private static final CommittedTransaction INBOUND_TXN = new CommittedTransaction(
            3001L, "SCH-IN-001",
            TransactionDirection.INBOUND, false,
            new BigDecimal("1.01010103"),
            new BigDecimal("1316.25000000"),
            new BigDecimal("38.99"), "USD",
            new BigDecimal("50000"), "KRW",
            new BigDecimal("37.04"),
            Instant.parse("2026-05-20T03:00:00Z"),
            42L);

    // OUTBOUND transaction — should appear in FX1014 file
    private static final CommittedTransaction OUTBOUND_TXN = new CommittedTransaction(
            3002L, "SCH-OUT-001",
            TransactionDirection.OUTBOUND, false,
            new BigDecimal("1.00500000"),
            new BigDecimal("0.99502488"),
            new BigDecimal("105.00"), "USD",
            new BigDecimal("100.00"), "USD",
            new BigDecimal("104.47"),
            Instant.parse("2026-05-20T04:00:00Z"),
            43L);

    // DOMESTIC transaction — must be filtered out
    private static final CommittedTransaction DOMESTIC_TXN = new CommittedTransaction(
            3003L, "SCH-DOM-001",
            TransactionDirection.DOMESTIC, true,
            null, null,
            new BigDecimal("15000"), "KRW",
            new BigDecimal("15000"), "KRW",
            null,
            Instant.parse("2026-05-20T05:00:00Z"),
            42L);

    private BokFxFileBuilder fileBuilder;

    @BeforeEach
    void setUp() {
        fileBuilder = new BokFxFileBuilder(tempDir.toString(), "GME_KR");
    }

    // =========================================================================
    // TEST 1: enabled=true → files are written with correct record counts
    // =========================================================================

    @Test
    @DisplayName("runForDate with enabled=true writes FX1014 and FX1015 files")
    void runForDate_enabled_writesBothFiles() throws Exception {
        TransactionClient stub = (from, to, partnerId) ->
                List.of(INBOUND_TXN, OUTBOUND_TXN, DOMESTIC_TXN);

        BokReportScheduler scheduler = new BokReportScheduler(stub, fileBuilder, true);
        scheduler.runForDate(REPORT_DATE);

        Path fx1014 = tempDir.resolve("BOK_FX1014_20260520.csv");
        Path fx1015 = tempDir.resolve("BOK_FX1015_20260520.csv");

        assertTrue(Files.exists(fx1014), "FX1014 file must be written when enabled=true");
        assertTrue(Files.exists(fx1015), "FX1015 file must be written when enabled=true");

        // FX1014 must contain one OUTBOUND data line
        long fx1014DataLines = Files.readAllLines(fx1014).stream()
                .filter(l -> !l.startsWith("#")).count();
        assertEquals(1L, fx1014DataLines,
                "FX1014 file must have exactly 1 data line (the OUTBOUND transaction)");

        // FX1015 must contain one INBOUND data line
        long fx1015DataLines = Files.readAllLines(fx1015).stream()
                .filter(l -> !l.startsWith("#")).count();
        assertEquals(1L, fx1015DataLines,
                "FX1015 file must have exactly 1 data line (the INBOUND transaction)");

        // DOMESTIC transaction must NOT appear in any file
        String fx1014Content = Files.readString(fx1014);
        String fx1015Content = Files.readString(fx1015);
        assertFalse(fx1014Content.contains("SCH-DOM-001"),
                "Domestic txn ref must not appear in FX1014 file");
        assertFalse(fx1015Content.contains("SCH-DOM-001"),
                "Domestic txn ref must not appear in FX1015 file");
    }

    // =========================================================================
    // TEST 2: enabled=false → no files written (gating works)
    // =========================================================================

    @Test
    @DisplayName("runForDate with enabled=false does not write any files")
    void runForDate_disabled_noFilesWritten() {
        TransactionClient stub = (from, to, partnerId) ->
                List.of(INBOUND_TXN, OUTBOUND_TXN);

        BokReportScheduler scheduler = new BokReportScheduler(stub, fileBuilder, false);
        scheduler.runForDate(REPORT_DATE);

        Path fx1014 = tempDir.resolve("BOK_FX1014_20260520.csv");
        Path fx1015 = tempDir.resolve("BOK_FX1015_20260520.csv");

        assertFalse(Files.exists(fx1014),
                "FX1014 file must NOT be written when enabled=false");
        assertFalse(Files.exists(fx1015),
                "FX1015 file must NOT be written when enabled=false");
    }

    // =========================================================================
    // TEST 3: Empty transaction list — files written with zero data lines
    // =========================================================================

    @Test
    @DisplayName("runForDate with empty transaction list writes empty files")
    void runForDate_emptyTransactions_writesEmptyFiles() throws Exception {
        TransactionClient emptyStub = (from, to, partnerId) -> List.of();

        BokReportScheduler scheduler = new BokReportScheduler(emptyStub, fileBuilder, true);
        scheduler.runForDate(REPORT_DATE);

        Path fx1014 = tempDir.resolve("BOK_FX1014_20260520.csv");
        assertTrue(Files.exists(fx1014), "FX1014 file must exist even with no transactions");

        long dataLines = Files.readAllLines(fx1014).stream()
                .filter(l -> !l.startsWith("#")).count();
        assertEquals(0L, dataLines, "FX1014 file must have 0 data lines for empty input");
    }

    // =========================================================================
    // TEST 4: offerRateColl (BOK FX1015 field #14) is preserved in file output
    // =========================================================================

    @Test
    @DisplayName("FX1015 file output preserves offerRateColl (BOK field #14) correctly")
    void runForDate_fx1015_preservesOfferRateColl() throws Exception {
        TransactionClient stub = (from, to, partnerId) -> List.of(INBOUND_TXN);

        BokReportScheduler scheduler = new BokReportScheduler(stub, fileBuilder, true);
        scheduler.runForDate(REPORT_DATE);

        Path fx1015 = tempDir.resolve("BOK_FX1015_20260520.csv");
        assertTrue(Files.exists(fx1015));

        String content = Files.readString(fx1015);
        // Col 13 = offerRateColl = 1.01010103
        assertTrue(content.contains("1.01010103"),
                "FX1015 file must contain offerRateColl=1.01010103 (BOK FX1015 field #14)");
    }
}
