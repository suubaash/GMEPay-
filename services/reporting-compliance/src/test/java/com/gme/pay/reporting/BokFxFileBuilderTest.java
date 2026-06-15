package com.gme.pay.reporting;

import com.gme.pay.reporting.bok.BokFxFileBuilder;
import com.gme.pay.reporting.domain.BokFxMapper;
import com.gme.pay.reporting.domain.BokFxRecord;
import com.gme.pay.reporting.domain.BokReportType;
import com.gme.pay.reporting.domain.CommittedTransaction;
import com.gme.pay.reporting.domain.TransactionDirection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Golden-row tests for {@link BokFxFileBuilder}.
 *
 * <p>Verifies that FX1014 and FX1015 records are written to the correct files with
 * the correct pipe-delimited column layout. No Spring context, no Mockito.
 */
class BokFxFileBuilderTest {

    @TempDir
    Path tempDir;

    private BokFxFileBuilder fileBuilder;
    private BokFxMapper mapper;

    // Canonical FX1014 (OUTBOUND) transaction — GME customer paying overseas
    private static final CommittedTransaction OUTBOUND_TXN = new CommittedTransaction(
            2001L,
            "TXN-OUT-2026-001",
            TransactionDirection.OUTBOUND,
            false,
            new BigDecimal("1.00500000"),      // offerRateColl
            new BigDecimal("0.99502488"),       // crossRate
            new BigDecimal("105.00"),           // collectionAmount (USD)
            "USD",
            new BigDecimal("100.00"),           // payoutAmount (USD)
            "USD",
            new BigDecimal("104.47"),           // usdAmount
            Instant.parse("2026-03-10T09:00:00Z"),
            43L
    );

    // Canonical FX1015 (INBOUND) transaction — payment to Korean merchant
    private static final CommittedTransaction INBOUND_TXN = new CommittedTransaction(
            2002L,
            "TXN-IN-2026-001",
            TransactionDirection.INBOUND,
            false,
            new BigDecimal("1.01010103"),       // offerRateColl — BOK FX1015 field #14
            new BigDecimal("1316.25000000"),    // crossRate
            new BigDecimal("38.9867"),          // collectionAmount (USD)
            "USD",
            new BigDecimal("50000"),            // payoutAmount (KRW)
            "KRW",
            new BigDecimal("37.0370"),          // usdAmount
            Instant.parse("2026-03-10T10:00:00Z"),
            42L
    );

    private static final LocalDate REPORT_DATE = LocalDate.of(2026, 3, 10);

    @BeforeEach
    void setUp() {
        fileBuilder = new BokFxFileBuilder(tempDir.toString(), "GME_KR");
        mapper = new BokFxMapper();
    }

    // =========================================================================
    // TEST 1: FX1014 golden row — OUTBOUND
    // =========================================================================

    @Test
    @DisplayName("FX1014 golden row: OUTBOUND transaction produces correct pipe-delimited line")
    void fx1014_goldenRow_outbound() throws IOException {
        BokFxRecord record = mapper.toRecord(OUTBOUND_TXN);
        assertEquals(BokReportType.FX1014, record.getReportType());

        String line = fileBuilder.buildLine(record, BokReportType.FX1014, REPORT_DATE);
        String[] cols = line.split("\\|", -1);

        // Col 1: record_type = "1014"
        assertEquals("1014", cols[0].trim(), "Col 1 record_type must be 1014 for OUTBOUND");

        // Col 2: report_date = YYYYMMDD
        assertEquals("20260310", cols[1], "Col 2 report_date must be 20260310");

        // Col 3: entity_id (right-padded to 10)
        assertEquals("GME_KR    ", cols[2], "Col 3 entity_id must be right-padded to 10 chars");

        // Col 4: partner_id = 43
        assertEquals("43", cols[3], "Col 4 partner_id must be 43");

        // Col 5: txn_ref (right-padded to 30)
        assertTrue(cols[4].startsWith("TXN-OUT-2026-001"),
                "Col 5 txn_ref must start with TXN-OUT-2026-001");

        // Col 6: txn_id = 2001
        assertEquals("2001", cols[5], "Col 6 txn_id must be 2001");

        // Col 7: collection_ccy = USD
        assertEquals("USD", cols[6].trim(), "Col 7 source currency must be USD");

        // Col 8: collection_amount = 105.00
        assertEquals("105.00", cols[7], "Col 8 collection_amount must be 105.00 (2dp)");

        // Col 9: payout_ccy = USD
        assertEquals("USD", cols[8].trim(), "Col 9 payout_ccy must be USD");

        // Col 10: payout_amount = 100.00
        assertEquals("100.00", cols[9], "Col 10 payout_amount must be 100.00 (2dp)");

        // Col 11: exchange_rate = crossRate 8dp
        assertEquals("0.99502488", cols[10], "Col 11 exchange_rate must be 0.99502488");

        // Col 12: usd_equivalent = usdAmount 2dp
        assertEquals("104.47", cols[11], "Col 12 usd_equivalent must be 104.47");

        // Col 13: offer_rate_coll 8dp
        assertEquals("1.00500000", cols[12], "Col 13 offer_rate_coll must be 1.00500000");

        // Col 14: submission_status = PENDING (padded)
        assertEquals("PENDING   ", cols[13], "Col 14 submission_status must be PENDING padded to 10");

        // Col 15: TODO(OI-03) placeholder
        assertEquals("TODO_OI03", cols[14], "Col 15 must be TODO_OI03 placeholder");

        // Col 16: TODO(OI-03) placeholder
        assertEquals("TODO_OI03", cols[15], "Col 16 must be TODO_OI03 placeholder");

        assertEquals(16, cols.length, "Row must have exactly 16 columns");
    }

    // =========================================================================
    // TEST 2: FX1015 golden row — INBOUND (offerRateColl = BOK field #14)
    // =========================================================================

    @Test
    @DisplayName("FX1015 golden row: INBOUND transaction produces correct pipe-delimited line with offerRateColl")
    void fx1015_goldenRow_inbound() throws IOException {
        BokFxRecord record = mapper.toRecord(INBOUND_TXN);
        assertEquals(BokReportType.FX1015, record.getReportType());

        String line = fileBuilder.buildLine(record, BokReportType.FX1015, REPORT_DATE);
        String[] cols = line.split("\\|", -1);

        // Col 1: record_type = "1015"
        assertEquals("1015", cols[0].trim(), "Col 1 record_type must be 1015 for INBOUND");

        // Col 5: txn_ref
        assertTrue(cols[4].startsWith("TXN-IN-2026-001"),
                "Col 5 txn_ref must start with TXN-IN-2026-001");

        // Col 7: collection_ccy = USD
        assertEquals("USD", cols[6].trim(), "Col 7 source currency must be USD");

        // Col 8: collection_amount = 38.99 (2dp rounding of 38.9867)
        assertEquals("38.99", cols[7],
                "Col 8 collection_amount 38.9867 must round to 38.99 at 2dp (HALF_UP)");

        // Col 9: payout_ccy = KRW
        assertEquals("KRW", cols[8].trim(), "Col 9 payout_ccy must be KRW");

        // Col 10: payout_amount = 50000.00
        assertEquals("50000.00", cols[9], "Col 10 payout_amount must be 50000.00");

        // Col 11: exchange_rate = crossRate 8dp
        assertEquals("1316.25000000", cols[10], "Col 11 exchange_rate must be 1316.25000000");

        // Col 12: usd_equivalent = 37.04
        assertEquals("37.04", cols[11], "Col 12 usd_equivalent must be 37.04 (2dp of 37.0370)");

        // Col 13: offer_rate_coll = BOK FX1015 field #14
        assertEquals("1.01010103", cols[12],
                "Col 13 offer_rate_coll must be 1.01010103 (BOK FX1015 field #14)");

        assertEquals(16, cols.length, "Row must have exactly 16 columns");
    }

    // =========================================================================
    // TEST 3: buildFiles writes both files to the outbound dir
    // =========================================================================

    @Test
    @DisplayName("buildFiles writes FX1014 and FX1015 files to the configured directory")
    void buildFiles_writesBothFilesToOutboundDir() throws IOException {
        BokFxRecord outboundRecord = mapper.toRecord(OUTBOUND_TXN);
        BokFxRecord inboundRecord = mapper.toRecord(INBOUND_TXN);

        BokFxFileBuilder.BokFileResult result =
                fileBuilder.buildFiles(List.of(outboundRecord, inboundRecord), REPORT_DATE);

        // Both files should exist
        assertTrue(Files.exists(result.getFx1014Path()),
                "FX1014 file must exist: " + result.getFx1014Path());
        assertTrue(Files.exists(result.getFx1015Path()),
                "FX1015 file must exist: " + result.getFx1015Path());

        // File names include the date
        assertEquals("BOK_FX1014_20260310.csv", result.getFx1014Path().getFileName().toString());
        assertEquals("BOK_FX1015_20260310.csv", result.getFx1015Path().getFileName().toString());

        // Record counts
        assertEquals(1, result.getFx1014Count(), "FX1014 should have 1 outbound record");
        assertEquals(1, result.getFx1015Count(), "FX1015 should have 1 inbound record");

        // File content: FX1014 must contain "1014" col 1
        List<String> fx1014Lines = Files.readAllLines(result.getFx1014Path());
        String fx1014DataLine = fx1014Lines.stream()
                .filter(l -> !l.startsWith("#"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("FX1014 file has no data line"));
        assertTrue(fx1014DataLine.startsWith("1014|"),
                "FX1014 data line must start with '1014|'");

        // File content: FX1015 must contain "1015" col 1
        List<String> fx1015Lines = Files.readAllLines(result.getFx1015Path());
        String fx1015DataLine = fx1015Lines.stream()
                .filter(l -> !l.startsWith("#"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("FX1015 file has no data line"));
        assertTrue(fx1015DataLine.startsWith("1015|"),
                "FX1015 data line must start with '1015|'");
    }

    // =========================================================================
    // TEST 4: Empty record list produces header-only files (not an error)
    // =========================================================================

    @Test
    @DisplayName("buildFiles with empty list produces header-only files (no data lines)")
    void buildFiles_emptyList_producesHeaderOnlyFile() throws IOException {
        BokFxFileBuilder.BokFileResult result =
                fileBuilder.buildFiles(List.of(), REPORT_DATE);

        assertTrue(Files.exists(result.getFx1014Path()), "FX1014 file must exist even when empty");
        assertTrue(Files.exists(result.getFx1015Path()), "FX1015 file must exist even when empty");

        assertEquals(0, result.getFx1014Count());
        assertEquals(0, result.getFx1015Count());

        // Files should contain only comment/header lines
        long fx1014DataLines = Files.readAllLines(result.getFx1014Path()).stream()
                .filter(l -> !l.startsWith("#")).count();
        assertEquals(0L, fx1014DataLines, "FX1014 file must have 0 data lines when empty");
    }
}
