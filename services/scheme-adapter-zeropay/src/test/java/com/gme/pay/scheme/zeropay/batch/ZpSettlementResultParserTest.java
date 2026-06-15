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
 * Unit tests for ZpSettlementResultParser — handles ZP0062 (morning) and ZP0064 (afternoon)
 * inbound settlement result files.
 *
 * <p>No Spring context, no database, no Docker.</p>
 */
class ZpSettlementResultParserTest {

    private final ZpSettlementResultParser parserZp0062 =
            new ZpSettlementResultParser("ZP0062");
    private final ZpSettlementResultParser parserZp0064 =
            new ZpSettlementResultParser("ZP0064");

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Builds a ZP0062/ZP0064 header (50 chars):
     * fileType(6) + date(8) + institutionCode(10) + count(6) + totalNet(15) + reserved(5)
     */
    private static String buildHeader(String fileType, int recordCount, long totalNet) {
        return String.format("%-6s20261014GMEPAY0001%06d%015d%-5s",
                fileType, recordCount, totalNet, "");
    }

    /**
     * Builds a ZP0062/ZP0064 trailer (28 chars):
     * T + count(6) + controlSum(15) + reserved(6)
     */
    private static String buildTrailer(int recordCount, long controlSum) {
        return String.format("T%06d%015d%-6s", recordCount, controlSum, "");
    }

    /**
     * Builds a 120-char ZP0062/ZP0064 detail line.
     * D + merchantId(10) + date(8) + payCount(6) + gross(15) + refundCount(6) + refund(15)
     *   + net(15) + merchantFee(15) + vanFee(15) + resultCode(4) + reserved(10)
     */
    private static String buildDetail(String merchantId, long gross, long refund, long net,
                                       String resultCode) {
        return String.format("D%-10s20261014%06d%015d%06d%015d%015d%015d%015d%-4s%-10s",
                merchantId, 5, gross, 1, refund, net,
                1000L, 300L, resultCode, "");
    }

    // -----------------------------------------------------------------------
    // Test 1: ZP0062 — parse valid single-merchant fixture
    // -----------------------------------------------------------------------

    @Test
    void parse_zp0062_validFixture_returnsOneRecord() {
        long gross = 100000L, refund = 5000L, net = 95000L;
        String detail = buildDetail("M000000001", gross, refund, net, "0000");
        String header = buildHeader("ZP0062", 1, net);
        String trailer = buildTrailer(1, net);
        String file = header + "\n" + detail + "\n" + trailer;

        List<ZpSettlementResultRecord> records =
                parserZp0062.parse(file.getBytes(StandardCharsets.UTF_8));

        assertEquals(1, records.size());
        assertEquals("M000000001", records.get(0).merchantId());
        assertEquals(new BigDecimal("95000"), records.get(0).confirmedNetKrw());
        assertEquals(LocalDate.of(2026, 10, 14), records.get(0).businessDate());
        assertEquals("0000", records.get(0).resultCode());
    }

    // -----------------------------------------------------------------------
    // Test 2: ZP0064 — parse valid single-merchant fixture
    // -----------------------------------------------------------------------

    @Test
    void parse_zp0064_validFixture_returnsOneRecord() {
        long gross = 200000L, refund = 10000L, net = 190000L;
        String detail = buildDetail("M000000002", gross, refund, net, "0000");
        String header = buildHeader("ZP0064", 1, net);
        String trailer = buildTrailer(1, net);
        String file = header + "\n" + detail + "\n" + trailer;

        List<ZpSettlementResultRecord> records =
                parserZp0064.parse(file.getBytes(StandardCharsets.UTF_8));

        assertEquals(1, records.size());
        assertEquals(new BigDecimal("190000"), records.get(0).confirmedNetKrw());
    }

    // -----------------------------------------------------------------------
    // Test 3: ZP0062 parser rejects ZP0064 file type
    // -----------------------------------------------------------------------

    @Test
    void parse_zp0062_givenZP0064File_throwsValidationError() {
        long net = 95000L;
        String detail = buildDetail("M000000001", 100000L, 5000L, net, "0000");
        String header = buildHeader("ZP0064", 1, net);  // wrong type for this parser
        String trailer = buildTrailer(1, net);
        String file = header + "\n" + detail + "\n" + trailer;

        ApiException ex = assertThrows(ApiException.class,
                () -> parserZp0062.parse(file.getBytes(StandardCharsets.UTF_8)));
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.errorCode());
        assertTrue(ex.getMessage().contains("ZP0064"));
    }

    // -----------------------------------------------------------------------
    // Test 4: trailer record count mismatch
    // -----------------------------------------------------------------------

    @Test
    void parse_trailerCountMismatch_throwsValidationError() {
        long net = 95000L;
        String detail = buildDetail("M000000001", 100000L, 5000L, net, "0000");
        String header = buildHeader("ZP0062", 3, net);  // claims 3 but only 1
        String trailer = buildTrailer(3, net);
        String file = header + "\n" + detail + "\n" + trailer;

        ApiException ex = assertThrows(ApiException.class,
                () -> parserZp0062.parse(file.getBytes(StandardCharsets.UTF_8)));
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.errorCode());
    }

    // -----------------------------------------------------------------------
    // Test 5: trailer control sum mismatch (sum of confirmedNetKrw)
    // -----------------------------------------------------------------------

    @Test
    void parse_controlSumMismatch_throwsValidationError() {
        long net = 95000L;
        String detail = buildDetail("M000000001", 100000L, 5000L, net, "0000");
        String header = buildHeader("ZP0062", 1, 99999L);  // wrong
        String trailer = buildTrailer(1, 99999L);           // wrong
        String file = header + "\n" + detail + "\n" + trailer;

        ApiException ex = assertThrows(ApiException.class,
                () -> parserZp0062.parse(file.getBytes(StandardCharsets.UTF_8)));
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.errorCode());
    }

    // -----------------------------------------------------------------------
    // Test 6: two-merchant fixture sums correctly
    // -----------------------------------------------------------------------

    @Test
    void parse_twoMerchants_controlSumAggregated() {
        long net1 = 95000L, net2 = 190000L, total = net1 + net2;
        String d1 = buildDetail("M000000001", 100000L, 5000L, net1, "0000");
        String d2 = buildDetail("M000000002", 200000L, 10000L, net2, "0000");
        String header = buildHeader("ZP0062", 2, total);
        String trailer = buildTrailer(2, total);
        String file = header + "\n" + d1 + "\n" + d2 + "\n" + trailer;

        List<ZpSettlementResultRecord> records =
                parserZp0062.parse(file.getBytes(StandardCharsets.UTF_8));
        assertEquals(2, records.size());
        assertEquals(new BigDecimal("95000"), records.get(0).confirmedNetKrw());
        assertEquals(new BigDecimal("190000"), records.get(1).confirmedNetKrw());
    }

    // -----------------------------------------------------------------------
    // Test 7: constructor rejects invalid file type
    // -----------------------------------------------------------------------

    @Test
    void constructor_invalidFileType_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> new ZpSettlementResultParser("ZP0011"));
    }
}
