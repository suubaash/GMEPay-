package com.gme.pay.scheme.zeropay.batch;

import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Zp0012FileParser — inbound payment-result-response file.
 * Validates header type check, trailer count/sum validation, and malformed-input rejection.
 *
 * <p>No Spring context, no database, no Docker.</p>
 */
class Zp0012ParserTest {

    private final Zp0012FileParser parser = new Zp0012FileParser();

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Builds a syntactically-valid ZP0012 fixture with one detail record.
     * Header (45 chars) + detail (110 chars) + trailer (22 chars).
     */
    private static String buildFixture(String fileType, int trailerCount, long trailerSum,
                                        String detailLine) {
        // header: fileType(6) + date(8) + institutionCode(10) + count(6) + total(15)
        String header = String.format("%-6s20261014GMEPAY0001%06d%015d",
                fileType, trailerCount, trailerSum);
        // trailer: T + count(6) + sum(15)
        String trailer = String.format("T%06d%015d", trailerCount, trailerSum);
        return header + "\n" + detailLine + "\n" + trailer;
    }

    /**
     * Builds a single 110-char ZP0012 detail line.
     */
    private static String buildDetail(String gmeTxnId, String zpRef, String merchantId,
                                       String businessDate, long amount,
                                       String resultCode, String resultMessage) {
        // D + gmeTxnId(20) + zpRef(20) + merchantId(10) + date(8) + amount(12)
        //   + resultCode(4) + resultMessage(20) + reserved(15)
        return String.format("D%-20s%-20s%-10s%s%012d%-4s%-20s%-15s",
                gmeTxnId, zpRef, merchantId, businessDate, amount,
                resultCode, resultMessage, "");
    }

    // -----------------------------------------------------------------------
    // Test 1: parse a valid single-record ZP0012 fixture
    // -----------------------------------------------------------------------

    @Test
    void parse_validFixture_returnsOneRecord() {
        String detail = buildDetail("TX000000000000000001", "ZP000000000000000001",
                "M000000001", "20261014", 50000L, "0000", "OK");
        String file = buildFixture("ZP0012", 1, 50000L, detail);
        List<Zp0012Record> records = parser.parse(file.getBytes(StandardCharsets.UTF_8));
        assertEquals(1, records.size());
        assertEquals("TX000000000000000001", records.get(0).gmeTxnId());
        assertEquals(new BigDecimal("50000"), records.get(0).payoutAmountKrw());
        assertEquals("0000", records.get(0).resultCode());
        assertEquals(LocalDate.of(2026, 10, 14), records.get(0).businessDate());
    }

    // -----------------------------------------------------------------------
    // Test 2: wrong file type in header -> ValidationError
    // -----------------------------------------------------------------------

    @Test
    void parse_wrongFileType_throwsValidationError() {
        String detail = buildDetail("TX000000000000000001", "ZP000000000000000001",
                "M000000001", "20261014", 50000L, "0000", "OK");
        // Use ZP0011 type code instead of ZP0012
        String file = buildFixture("ZP0011", 1, 50000L, detail);
        ApiException ex = assertThrows(ApiException.class,
                () -> parser.parse(file.getBytes(StandardCharsets.UTF_8)));
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.errorCode());
        assertTrue(ex.getMessage().contains("ZP0011"));
    }

    // -----------------------------------------------------------------------
    // Test 3: trailer record count mismatch -> ValidationError
    // -----------------------------------------------------------------------

    @Test
    void parse_trailerCountMismatch_throwsValidationError() {
        String detail = buildDetail("TX000000000000000001", "ZP000000000000000001",
                "M000000001", "20261014", 50000L, "0000", "OK");
        // Claim 2 records but only 1 detail line
        String file = buildFixture("ZP0012", 2, 50000L, detail);
        ApiException ex = assertThrows(ApiException.class,
                () -> parser.parse(file.getBytes(StandardCharsets.UTF_8)));
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.errorCode());
    }

    // -----------------------------------------------------------------------
    // Test 4: trailer control sum mismatch -> ValidationError
    // -----------------------------------------------------------------------

    @Test
    void parse_trailerControlSumMismatch_throwsValidationError() {
        String detail = buildDetail("TX000000000000000001", "ZP000000000000000001",
                "M000000001", "20261014", 50000L, "0000", "OK");
        // Control sum claims 99999 but actual amount is 50000
        String file = buildFixture("ZP0012", 1, 99999L, detail);
        ApiException ex = assertThrows(ApiException.class,
                () -> parser.parse(file.getBytes(StandardCharsets.UTF_8)));
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.errorCode());
    }

    // -----------------------------------------------------------------------
    // Test 5: truncated file (< 2 lines) -> ValidationError
    // -----------------------------------------------------------------------

    @Test
    void parse_truncatedFile_throwsValidationError() {
        String shortFile = "ZP001220261014GMEPAY0001000001000000000050000";
        ApiException ex = assertThrows(ApiException.class,
                () -> parser.parse(shortFile.getBytes(StandardCharsets.UTF_8)));
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.errorCode());
    }

    // -----------------------------------------------------------------------
    // Test 6: detail line too short -> ValidationError
    // -----------------------------------------------------------------------

    @Test
    void parse_shortDetailLine_throwsValidationError() {
        String header = String.format("%-6s20261014GMEPAY0001%06d%015d", "ZP0012", 1, 100);
        String shortDetail = "DSHORT"; // way too short
        String trailer = String.format("T%06d%015d", 1, 100);
        String file = header + "\n" + shortDetail + "\n" + trailer;
        ApiException ex = assertThrows(ApiException.class,
                () -> parser.parse(file.getBytes(StandardCharsets.UTF_8)));
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.errorCode());
    }
}
