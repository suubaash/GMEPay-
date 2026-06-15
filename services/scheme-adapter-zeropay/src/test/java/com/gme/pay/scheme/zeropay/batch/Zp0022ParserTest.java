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
 * Unit tests for Zp0022FileParser — inbound refund-result-response file.
 * Mirrors Zp0012ParserTest structure but uses refundAmountKrw as the control sum field.
 *
 * <p>No Spring context, no database, no Docker.</p>
 */
class Zp0022ParserTest {

    private final Zp0022FileParser parser = new Zp0022FileParser();

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static String buildFixture(String fileType, int trailerCount, long trailerSum,
                                        String detailLine) {
        String header = String.format("%-6s20261014GMEPAY0001%06d%015d",
                fileType, trailerCount, trailerSum);
        String trailer = String.format("T%06d%015d", trailerCount, trailerSum);
        return header + "\n" + detailLine + "\n" + trailer;
    }

    private static String buildDetail(String gmeTxnId, String zpRef, String merchantId,
                                       String businessDate, long refundAmount,
                                       String resultCode, String resultMessage) {
        return String.format("D%-20s%-20s%-10s%s%012d%-4s%-20s%-15s",
                gmeTxnId, zpRef, merchantId, businessDate, refundAmount,
                resultCode, resultMessage, "");
    }

    // -----------------------------------------------------------------------
    // Test 1: parse valid single-record ZP0022 fixture
    // -----------------------------------------------------------------------

    @Test
    void parse_validFixture_returnsOneRefundRecord() {
        String detail = buildDetail("TX000000000000000001", "ZP00000000000000REFD",
                "M000000001", "20261014", 30000L, "0000", "Refund OK");
        String file = buildFixture("ZP0022", 1, 30000L, detail);
        List<Zp0022Record> records = parser.parse(file.getBytes(StandardCharsets.UTF_8));
        assertEquals(1, records.size());
        assertEquals("TX000000000000000001", records.get(0).gmeTxnId());
        assertEquals(new BigDecimal("30000"), records.get(0).refundAmountKrw());
        assertEquals("0000", records.get(0).resultCode());
        assertEquals(LocalDate.of(2026, 10, 14), records.get(0).businessDate());
    }

    // -----------------------------------------------------------------------
    // Test 2: wrong file type in header -> ValidationError
    // -----------------------------------------------------------------------

    @Test
    void parse_wrongFileType_throwsValidationError() {
        String detail = buildDetail("TX000000000000000001", "ZP00000000000000REFD",
                "M000000001", "20261014", 30000L, "0000", "OK");
        String file = buildFixture("ZP0021", 1, 30000L, detail);
        ApiException ex = assertThrows(ApiException.class,
                () -> parser.parse(file.getBytes(StandardCharsets.UTF_8)));
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.errorCode());
        assertTrue(ex.getMessage().contains("ZP0021"));
    }

    // -----------------------------------------------------------------------
    // Test 3: trailer count mismatch -> ValidationError
    // -----------------------------------------------------------------------

    @Test
    void parse_trailerCountMismatch_throwsValidationError() {
        String detail = buildDetail("TX000000000000000001", "ZP00000000000000REFD",
                "M000000001", "20261014", 30000L, "0000", "OK");
        String file = buildFixture("ZP0022", 5, 30000L, detail); // claims 5 but only 1
        ApiException ex = assertThrows(ApiException.class,
                () -> parser.parse(file.getBytes(StandardCharsets.UTF_8)));
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.errorCode());
    }

    // -----------------------------------------------------------------------
    // Test 4: control sum mismatch -> ValidationError
    // -----------------------------------------------------------------------

    @Test
    void parse_controlSumMismatch_throwsValidationError() {
        String detail = buildDetail("TX000000000000000001", "ZP00000000000000REFD",
                "M000000001", "20261014", 30000L, "0000", "OK");
        String file = buildFixture("ZP0022", 1, 99999L, detail); // wrong sum
        ApiException ex = assertThrows(ApiException.class,
                () -> parser.parse(file.getBytes(StandardCharsets.UTF_8)));
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.errorCode());
    }

    // -----------------------------------------------------------------------
    // Test 5: multi-record fixture — sum of two refunds
    // -----------------------------------------------------------------------

    @Test
    void parse_twoRecords_controlSumIsAggregated() {
        String d1 = buildDetail("TX000000000000000001", "ZP00000000000000REF1",
                "M000000001", "20261014", 30000L, "0000", "OK");
        String d2 = buildDetail("TX000000000000000002", "ZP00000000000000REF2",
                "M000000002", "20261014", 15000L, "0000", "OK");
        String header = String.format("%-6s20261014GMEPAY0001%06d%015d", "ZP0022", 2, 45000L);
        String trailer = String.format("T%06d%015d", 2, 45000L);
        String file = header + "\n" + d1 + "\n" + d2 + "\n" + trailer;

        List<Zp0022Record> records = parser.parse(file.getBytes(StandardCharsets.UTF_8));
        assertEquals(2, records.size());
        assertEquals(new BigDecimal("30000"), records.get(0).refundAmountKrw());
        assertEquals(new BigDecimal("15000"), records.get(1).refundAmountKrw());
    }
}
