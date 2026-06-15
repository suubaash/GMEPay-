package com.gme.pay.merchant.sync;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ZeroPayMerchantFileParser}.
 *
 * <p>No Spring context, no Docker, no file I/O — uses in-memory {@link StringReader}.
 */
class ZeroPayMerchantFileParserTest {

    private final ZeroPayMerchantFileParser parser = new ZeroPayMerchantFileParser();

    // ------------------------------------------------------------------
    // ZP0041 incremental parsing
    // ------------------------------------------------------------------

    @Test
    void parseIncremental_happyPath_parsesAllColumns() throws IOException {
        String content = "MN|M0000000010|Gangnam Mart|RETAIL|DOMESTIC|ACTIVE|KRW|ZEROPAY|Seoul|5411\n";
        ParseResult<ParsedMerchantRow> result = parser.parseIncremental(new StringReader(content), "ZP0041_test.dat");

        assertTrue(result.isClean(), "Expected no parse errors");
        assertEquals(1, result.rows().size());
        ParsedMerchantRow row = result.rows().get(0);
        assertEquals("MN", row.recordType());
        assertEquals("M0000000010", row.merchantId());
        assertEquals("Gangnam Mart", row.name());
        assertEquals("RETAIL", row.merchantType());
        assertEquals("DOMESTIC", row.feeType());
        assertEquals("ACTIVE", row.status());
        assertEquals("KRW", row.payoutCurrency());
        assertEquals("ZEROPAY", row.schemeId());
        assertEquals("Seoul", row.city());
        assertEquals("5411", row.mcc());
        assertTrue(row.isActive());
        assertFalse(row.isDelete());
    }

    @Test
    void parseIncremental_mdRow_isDeleteTrue() throws IOException {
        String content = "MD|M0000000013|Closed Shop|RETAIL|DOMESTIC|DEACTIVATED|KRW|ZEROPAY|Busan|5411\n";
        ParseResult<ParsedMerchantRow> result = parser.parseIncremental(new StringReader(content), "ZP0041_test.dat");

        assertEquals(1, result.rows().size());
        assertTrue(result.rows().get(0).isDelete());
        assertFalse(result.rows().get(0).isActive());
    }

    @Test
    void parseIncremental_commentsAndBlanks_areSkipped() throws IOException {
        String content = "# This is a comment\n"
                + "\n"
                + "MN|M0000000010|Gangnam Mart|RETAIL|DOMESTIC|ACTIVE|KRW|ZEROPAY|Seoul|5411\n"
                + "  \n";
        ParseResult<ParsedMerchantRow> result = parser.parseIncremental(new StringReader(content), "ZP0041_test.dat");

        assertEquals(1, result.rows().size());
        assertEquals(3, result.skipped()); // comment + blank + whitespace-only
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void parseIncremental_tooFewColumns_skipsAndRecordsError() throws IOException {
        String content = "TOOFEWCOLUMNS|ONLY_TWO\n"
                + "MN|M0000000010|Gangnam Mart|RETAIL|DOMESTIC|ACTIVE|KRW|ZEROPAY|Seoul|5411\n";
        ParseResult<ParsedMerchantRow> result = parser.parseIncremental(new StringReader(content), "ZP0041_test.dat");

        assertEquals(1, result.rows().size(), "Valid row after malformed row must be parsed");
        assertEquals(1, result.errors().size());
        assertTrue(result.errors().get(0).contains("expected 10 columns"));
    }

    @Test
    void parseIncremental_blankRecordType_skipsAndRecordsError() throws IOException {
        String content = "|BLANK_TYPE|Merchant Name|RETAIL|DOMESTIC|ACTIVE|KRW|ZEROPAY|Seoul|5411\n";
        ParseResult<ParsedMerchantRow> result = parser.parseIncremental(new StringReader(content), "ZP0041_test.dat");

        assertEquals(0, result.rows().size());
        assertEquals(1, result.errors().size());
        assertTrue(result.errors().get(0).contains("record_type is blank"));
    }

    @Test
    void parseIncremental_blankMerchantId_skipsAndRecordsError() throws IOException {
        String content = "MN||Missing Merchant ID|RETAIL|DOMESTIC|ACTIVE|KRW|ZEROPAY|Seoul|5411\n";
        ParseResult<ParsedMerchantRow> result = parser.parseIncremental(new StringReader(content), "ZP0041_test.dat");

        assertEquals(0, result.rows().size());
        assertEquals(1, result.errors().size());
        assertTrue(result.errors().get(0).contains("merchant_id"));
    }

    @Test
    void parseIncremental_multipleRows_parsesAll() throws IOException {
        String content = "MN|M0000000010|Gangnam Mart|RETAIL|DOMESTIC|ACTIVE|KRW|ZEROPAY|Seoul|5411\n"
                + "MC|M0000000011|Updated Cafe|FOOD_BEVERAGE|DOMESTIC|ACTIVE|KRW|ZEROPAY|Busan|5812\n"
                + "MD|M0000000012|Gone Shop|RETAIL|DOMESTIC|DEACTIVATED|KRW|ZEROPAY|Incheon|5411\n";
        ParseResult<ParsedMerchantRow> result = parser.parseIncremental(new StringReader(content), "ZP0041_test.dat");

        assertEquals(3, result.rows().size());
        assertTrue(result.isClean());
        assertFalse(result.rows().get(0).isDelete());
        assertFalse(result.rows().get(1).isDelete());
        assertTrue(result.rows().get(2).isDelete());
    }

    // ------------------------------------------------------------------
    // ZP0051 full-list parsing
    // ------------------------------------------------------------------

    @Test
    void parseFullList_happyPath_noRecordTypeColumn() throws IOException {
        String content = "M0000000010|Gangnam Mart|RETAIL|DOMESTIC|ACTIVE|KRW|ZEROPAY|Seoul|5411\n";
        ParseResult<ParsedMerchantRow> result = parser.parseFullList(new StringReader(content), "ZP0051_test.dat");

        assertEquals(1, result.rows().size());
        assertTrue(result.isClean());
        ParsedMerchantRow row = result.rows().get(0);
        assertNull(row.recordType(), "Full-list rows must have null recordType");
        assertEquals("M0000000010", row.merchantId());
        assertEquals("Gangnam Mart", row.name());
        assertEquals("KRW", row.payoutCurrency());
        assertEquals("ZEROPAY", row.schemeId());
    }

    @Test
    void parseFullList_tooFewColumns_skipsAndRecordsError() throws IOException {
        String content = "M0000000010|OnlyTwoFields\n"
                + "M0000000011|Valid Row|RETAIL|DOMESTIC|ACTIVE|KRW|ZEROPAY|Seoul|5411\n";
        ParseResult<ParsedMerchantRow> result = parser.parseFullList(new StringReader(content), "ZP0051_test.dat");

        assertEquals(1, result.rows().size());
        assertEquals(1, result.errors().size());
        assertTrue(result.errors().get(0).contains("expected 9 columns"));
    }
}
