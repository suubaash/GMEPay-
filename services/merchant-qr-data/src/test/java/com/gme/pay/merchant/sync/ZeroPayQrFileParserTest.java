package com.gme.pay.merchant.sync;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ZeroPayQrFileParser}.
 *
 * <p>No Spring context, no Docker — uses in-memory {@link StringReader}.
 */
class ZeroPayQrFileParserTest {

    private final ZeroPayQrFileParser parser = new ZeroPayQrFileParser();

    // ------------------------------------------------------------------
    // ZP0043 incremental parsing
    // ------------------------------------------------------------------

    @Test
    void parseIncremental_qrRow_parsedAsActive() throws IOException {
        String content = "QR|QR00000000000000010A|M0000000010|ACTIVE\n";
        ParseResult<ParsedQrRow> result = parser.parseIncremental(new StringReader(content), "ZP0043_test.dat");

        assertTrue(result.isClean());
        assertEquals(1, result.rows().size());
        ParsedQrRow row = result.rows().get(0);
        assertEquals("QR", row.recordType());
        assertEquals("QR00000000000000010A", row.qrCode());
        assertEquals("M0000000010", row.merchantId());
        assertEquals("ACTIVE", row.status());
        assertTrue(row.isActive());
        assertFalse(row.isDeactivation());
    }

    @Test
    void parseIncremental_qdRow_parsedAsDeactivation() throws IOException {
        String content = "QD|QR00000000000000013D|M0000000013|DEACTIVATED\n";
        ParseResult<ParsedQrRow> result = parser.parseIncremental(new StringReader(content), "ZP0043_test.dat");

        assertEquals(1, result.rows().size());
        ParsedQrRow row = result.rows().get(0);
        assertTrue(row.isDeactivation());
        assertFalse(row.isActive());
        assertEquals("QR00000000000000013D", row.qrCode());
    }

    @Test
    void parseIncremental_commentsAndBlanks_skipped() throws IOException {
        String content = "# comment line\n"
                + "\n"
                + "QR|QR00000000000000010A|M0000000010|ACTIVE\n";
        ParseResult<ParsedQrRow> result = parser.parseIncremental(new StringReader(content), "ZP0043_test.dat");

        assertEquals(1, result.rows().size());
        assertEquals(2, result.skipped());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void parseIncremental_tooFewColumns_skipsAndRecordsError() throws IOException {
        String content = "ONLY_TWO_COLS|FIELD2\n"
                + "QR|QR00000000000000010A|M0000000010|ACTIVE\n";
        ParseResult<ParsedQrRow> result = parser.parseIncremental(new StringReader(content), "ZP0043_test.dat");

        assertEquals(1, result.rows().size());
        assertEquals(1, result.errors().size());
        assertTrue(result.errors().get(0).contains("expected 4 columns"));
    }

    @Test
    void parseIncremental_blankQrCode_skipsAndRecordsError() throws IOException {
        String content = "QR||M0000000010|ACTIVE\n";
        ParseResult<ParsedQrRow> result = parser.parseIncremental(new StringReader(content), "ZP0043_test.dat");

        assertEquals(0, result.rows().size());
        assertEquals(1, result.errors().size());
        assertTrue(result.errors().get(0).contains("qr_code"));
    }

    // ------------------------------------------------------------------
    // ZP0053 full-list parsing
    // ------------------------------------------------------------------

    @Test
    void parseFullList_happyPath_noRecordTypeColumn() throws IOException {
        String content = "QR00000000000000010A|M0000000010|ACTIVE\n";
        ParseResult<ParsedQrRow> result = parser.parseFullList(new StringReader(content), "ZP0053_test.dat");

        assertEquals(1, result.rows().size());
        assertTrue(result.isClean());
        ParsedQrRow row = result.rows().get(0);
        assertNull(row.recordType(), "Full-list rows must have null recordType");
        assertEquals("QR00000000000000010A", row.qrCode());
        assertEquals("M0000000010", row.merchantId());
        assertEquals("ACTIVE", row.status());
        assertTrue(row.isActive());
    }

    @Test
    void parseFullList_tooFewColumns_skipsAndRecordsError() throws IOException {
        String content = "ONLY_ONE_FIELD\n"
                + "QR00000000000000010A|M0000000010|ACTIVE\n";
        ParseResult<ParsedQrRow> result = parser.parseFullList(new StringReader(content), "ZP0053_test.dat");

        assertEquals(1, result.rows().size());
        assertEquals(1, result.errors().size());
        assertTrue(result.errors().get(0).contains("expected 3 columns"));
    }
}
