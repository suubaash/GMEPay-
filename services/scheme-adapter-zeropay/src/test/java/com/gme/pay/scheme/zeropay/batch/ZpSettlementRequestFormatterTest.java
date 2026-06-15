package com.gme.pay.scheme.zeropay.batch;

import com.gme.pay.scheme.zeropay.adapter.model.BatchType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ZP0061/ZP0063 settlement request fixed-width file format.
 * No Spring context, no database, no Docker.
 */
class ZpSettlementRequestFormatterTest {

    private static final String INSTITUTION_CODE = "GMEPAY0001";
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 10, 14);

    private final ZpSettlementRequestFormatter formatter =
            new ZpSettlementRequestFormatter(INSTITUTION_CODE);

    private ZpSettlementRequestRecord sampleRecord(String merchantId,
                                                    BigDecimal gross, BigDecimal refund) {
        BigDecimal net = gross.subtract(refund);
        return new ZpSettlementRequestRecord(
                merchantId,
                BUSINESS_DATE,
                /* paymentCount */ 5,
                gross,
                /* refundCount */ 1,
                refund,
                net,
                new BigDecimal("1000"),
                new BigDecimal("300")
        );
    }

    // -----------------------------------------------------------------------
    // Test 1: ZP0061 header starts with correct type code
    // -----------------------------------------------------------------------

    @Test
    void format_zp0061_headerStartsWithZP0061() {
        byte[] bytes = formatter.format(BatchType.ZP0061, BUSINESS_DATE,
                List.of(sampleRecord("M000000001", new BigDecimal("100000"), new BigDecimal("5000"))));
        String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\n");
        assertEquals("ZP0061", lines[0].substring(0, 6));
        assertEquals("20261014", lines[0].substring(6, 14));
    }

    // -----------------------------------------------------------------------
    // Test 2: ZP0063 header starts with correct type code
    // -----------------------------------------------------------------------

    @Test
    void format_zp0063_headerStartsWithZP0063() {
        byte[] bytes = formatter.format(BatchType.ZP0063, BUSINESS_DATE,
                List.of(sampleRecord("M000000001", new BigDecimal("200000"), new BigDecimal("10000"))));
        String firstLine = new String(bytes, StandardCharsets.UTF_8).split("\n")[0];
        assertEquals("ZP0063", firstLine.substring(0, 6));
    }

    // -----------------------------------------------------------------------
    // Test 3: detail record length is 120
    // -----------------------------------------------------------------------

    @Test
    void format_detailRecordLengthIs120() {
        byte[] bytes = formatter.format(BatchType.ZP0061, BUSINESS_DATE,
                List.of(sampleRecord("M000000001", new BigDecimal("100000"), new BigDecimal("5000"))));
        String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\n");
        // lines[0]=header, lines[1]=detail, lines[2]=trailer
        assertEquals(ZpSettlementRequestRecord.RECORD_LENGTH, lines[1].length());
    }

    // -----------------------------------------------------------------------
    // Test 4: trailer control sum = sum of all net amounts
    // -----------------------------------------------------------------------

    @Test
    void format_trailerControlSumIsNetSum() {
        ZpSettlementRequestRecord r1 = sampleRecord("M000000001",
                new BigDecimal("100000"), new BigDecimal("5000"));   // net = 95000
        ZpSettlementRequestRecord r2 = sampleRecord("M000000002",
                new BigDecimal("200000"), new BigDecimal("10000"));  // net = 190000
        byte[] bytes = formatter.format(BatchType.ZP0061, BUSINESS_DATE, List.of(r1, r2));
        String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\n");
        String trailer = lines[lines.length - 1];
        // controlSum at offset 7, length 15
        String controlSum = trailer.substring(7, 7 + 15);
        assertEquals("000000000285000", controlSum);  // 95000 + 190000
    }

    // -----------------------------------------------------------------------
    // Test 5: empty day produces header + trailer only
    // -----------------------------------------------------------------------

    @Test
    void format_emptyDay_headerAndTrailerOnly() {
        byte[] bytes = formatter.format(BatchType.ZP0061, BUSINESS_DATE, List.of());
        String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\n");
        assertEquals(2, lines.length);
        assertTrue(lines[0].startsWith("ZP0061"));
        assertEquals('T', lines[1].charAt(0));
    }

    // -----------------------------------------------------------------------
    // Test 6: unknown type code throws IllegalArgumentException
    // -----------------------------------------------------------------------

    @Test
    void format_unknownBatchType_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> formatter.format(BatchType.ZP0011, BUSINESS_DATE, List.of()));
    }

    // -----------------------------------------------------------------------
    // Test 7: merchant ID is CHAR(10) at offset 1
    // -----------------------------------------------------------------------

    @Test
    void format_merchantIdIsPaddedAt10CharsFromOffset1() {
        ZpSettlementRequestRecord r = sampleRecord("M01",
                new BigDecimal("50000"), new BigDecimal("0"));
        byte[] bytes = formatter.format(BatchType.ZP0061, BUSINESS_DATE, List.of(r));
        String detail = new String(bytes, StandardCharsets.UTF_8).split("\n")[1];
        String merchantIdField = detail.substring(
                ZpSettlementRequestRecord.MERCHANT_ID_OFFSET,
                ZpSettlementRequestRecord.MERCHANT_ID_OFFSET + ZpSettlementRequestRecord.MERCHANT_ID_LEN);
        assertEquals("M01       ", merchantIdField);
    }
}
