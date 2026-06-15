package com.gme.pay.reporting.kofiu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link KofiuFeedFileBuilder} — verifies feed file shape.
 *
 * <p>Uses the package-private {@link KofiuFeedFileBuilder#buildContent(KofiuReportBatch)}
 * method directly for content assertions, and the full
 * {@link KofiuFeedFileBuilder#buildAndWrite(KofiuReportBatch)} for I/O assertions.
 */
class KofiuFeedFileBuilderTest {

    private static final LocalDate REPORT_DATE = LocalDate.of(2026, 6, 15);
    private static final String ENTITY_ID = "GME_TEST";
    private static final Instant INSTANT = Instant.parse("2026-06-15T02:00:00Z");

    // =========================================================================
    // TEST 1: header line contains report date + entity id
    // =========================================================================

    @Test
    @DisplayName("Feed file: header line contains H sentinel, report date, and entity id")
    void header_containsDateAndEntityId() {
        KofiuFeedFileBuilder builder = builderWith(ENTITY_ID);
        KofiuReportBatch batch = new KofiuReportBatch(REPORT_DATE, List.of(), List.of());

        String content = builder.buildContent(batch);

        String headerLine = content.lines().findFirst().orElseThrow();
        assertTrue(headerLine.startsWith("H|"),
                "Header line must start with 'H|'");
        assertTrue(headerLine.contains("20260615"),
                "Header must contain date formatted as yyyyMMdd");
        assertTrue(headerLine.contains(ENTITY_ID),
                "Header must contain the entity id");
    }

    // =========================================================================
    // TEST 2: CTR record line contains correct fields
    // =========================================================================

    @Test
    @DisplayName("Feed file: CTR record line contains end-user, amount, and txn count")
    void ctrRecord_correctFields() {
        CtrReport ctr = new CtrReport(
                "END-USER-001", 42L, REPORT_DATE,
                new BigDecimal("12000000"), 3,
                List.of(101L, 102L, 103L));
        KofiuReportBatch batch = new KofiuReportBatch(REPORT_DATE, List.of(ctr), List.of());

        String content = builderWith(ENTITY_ID).buildContent(batch);

        String ctrLine = content.lines()
                .filter(l -> l.startsWith("CTR|"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No CTR line in output"));
        assertTrue(ctrLine.contains("END-USER-001"), "CTR line must contain endUserId");
        assertTrue(ctrLine.contains("12000000"), "CTR line must contain total amount");
        assertTrue(ctrLine.contains("3"), "CTR line must contain txn count");
        // Money must be plain decimal, not scientific notation (e.g. must not match 1.2E7)
        assertFalse(ctrLine.matches(".*\\d[Ee][+-]?\\d.*"),
                "CTR amount must not use scientific notation");
    }

    // =========================================================================
    // TEST 3: STR record line contains correct fields
    // =========================================================================

    @Test
    @DisplayName("Feed file: STR record line contains txnRef, end-user, and corridor ccy")
    void strRecord_correctFields() {
        StrReport str = new StrReport(
                201L, "TXN-REF-201", "END-USER-002", 42L, REPORT_DATE,
                new BigDecimal("500000"), "KRW", "PHP");
        KofiuReportBatch batch = new KofiuReportBatch(REPORT_DATE, List.of(), List.of(str));

        String content = builderWith(ENTITY_ID).buildContent(batch);

        String strLine = content.lines()
                .filter(l -> l.startsWith("STR|"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No STR line in output"));
        assertTrue(strLine.contains("TXN-REF-201"), "STR line must contain txnRef");
        assertTrue(strLine.contains("END-USER-002"), "STR line must contain endUserId");
        assertTrue(strLine.contains("KRW"), "STR line must contain srcCcy");
        assertTrue(strLine.contains("PHP"), "STR line must contain dstCcy");
        // Money must be plain decimal, not scientific notation (e.g. must not match 5E5)
        assertFalse(strLine.matches(".*\\d[Ee][+-]?\\d.*"),
                "STR amount must not use scientific notation");
    }

    // =========================================================================
    // TEST 4: trailer line reflects total record count
    // =========================================================================

    @Test
    @DisplayName("Feed file: trailer line reflects total CTR + STR count")
    void trailer_totalCount() {
        CtrReport ctr = new CtrReport(
                "EU-1", 1L, REPORT_DATE, new BigDecimal("10000000"), 1, List.of(1L));
        StrReport str = new StrReport(
                2L, "REF-2", "EU-2", 1L, REPORT_DATE, new BigDecimal("500000"), "KRW", "USD");
        KofiuReportBatch batch = new KofiuReportBatch(REPORT_DATE, List.of(ctr), List.of(str));

        String content = builderWith(ENTITY_ID).buildContent(batch);

        String trailerLine = content.lines()
                .filter(l -> l.startsWith("T|"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No trailer line in output"));
        assertTrue(trailerLine.contains("2"),
                "Trailer must report total of 2 records (1 CTR + 1 STR)");
    }

    // =========================================================================
    // TEST 5: empty batch still produces header + trailer
    // =========================================================================

    @Test
    @DisplayName("Feed file: empty batch produces header and trailer (no CTR/STR lines)")
    void emptyBatch_headerAndTrailerOnly() {
        KofiuReportBatch batch = new KofiuReportBatch(REPORT_DATE, List.of(), List.of());
        String content = builderWith(ENTITY_ID).buildContent(batch);

        long ctrLines = content.lines().filter(l -> l.startsWith("CTR|")).count();
        long strLines = content.lines().filter(l -> l.startsWith("STR|")).count();
        boolean hasHeader = content.lines().anyMatch(l -> l.startsWith("H|"));
        boolean hasTrailer = content.lines().anyMatch(l -> l.startsWith("T|"));

        assertEquals(0, ctrLines);
        assertEquals(0, strLines);
        assertTrue(hasHeader, "Empty batch must still produce a header line");
        assertTrue(hasTrailer, "Empty batch must still produce a trailer line");
    }

    // =========================================================================
    // TEST 6: buildAndWrite creates the file on disk
    // =========================================================================

    @Test
    @DisplayName("Feed file: buildAndWrite creates the KOFIU_YYYYMMDD.dat file in output dir")
    void buildAndWrite_createsFile(@TempDir Path tempDir) {
        KofiuFeedFileBuilder builder = new KofiuFeedFileBuilder(
                tempDir.toString(), ENTITY_ID);
        KofiuReportBatch batch = new KofiuReportBatch(REPORT_DATE, List.of(), List.of());

        Path written = builder.buildAndWrite(batch);

        assertTrue(written.toFile().exists(), "Feed file must exist after buildAndWrite");
        assertEquals("KOFIU_20260615.dat", written.getFileName().toString());
    }

    // =========================================================================
    // Helper
    // =========================================================================

    private KofiuFeedFileBuilder builderWith(String entityId) {
        return new KofiuFeedFileBuilder("/tmp/kofiu-test", entityId);
    }
}
