package com.gme.pay.scheme.zeropay.batch;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ZP0066 refund-detail fixed-width file format.
 * No Spring context, no database, no Docker.
 */
class Zp0066RoundTripTest {

    private static final String INSTITUTION_CODE = "GMEPAY0001";
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 10, 14);
    private static final LocalDate SETTLEMENT_DATE = LocalDate.of(2026, 10, 15);

    private final Zp0066FileFormatter formatter = new Zp0066FileFormatter(INSTITUTION_CODE);

    private Zp0066Record sampleRecord(String gmeTxnId, BigDecimal refundAmount) {
        return new Zp0066Record(
                gmeTxnId,
                "ZP00000000000000REFD",
                "M000000001",
                "QR000000000012345678",
                BUSINESS_DATE,
                LocalTime.of(21, 50, 0),
                refundAmount,
                new BigDecimal("300"),
                new BigDecimal("100"),
                'D',
                "AP0000000001",
                'R',
                SETTLEMENT_DATE
        );
    }

    // -----------------------------------------------------------------------
    // Test 1: golden-row — header starts ZP0066
    // -----------------------------------------------------------------------

    @Test
    void format_headerStartsWithZP0066() {
        byte[] bytes = formatter.format(BUSINESS_DATE,
                List.of(sampleRecord("TX0000000000000R001", new BigDecimal("25000"))));
        String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\n");
        assertEquals("ZP0066", lines[0].substring(0, 6));
    }

    // -----------------------------------------------------------------------
    // Test 2: detail record length is 150
    // -----------------------------------------------------------------------

    @Test
    void format_detailRecordLengthIs150() {
        byte[] bytes = formatter.format(BUSINESS_DATE,
                List.of(sampleRecord("TX0000000000000R001", new BigDecimal("25000"))));
        String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\n");
        assertEquals(Zp0066Record.RECORD_LENGTH, lines[1].length());
    }

    // -----------------------------------------------------------------------
    // Test 3: trailer control sum = sum of refund amounts
    // -----------------------------------------------------------------------

    @Test
    void format_trailerControlSumIsRefundSum() {
        Zp0066Record r1 = sampleRecord("TX0000000000000R001", new BigDecimal("25000"));
        Zp0066Record r2 = sampleRecord("TX0000000000000R002", new BigDecimal("15000"));
        byte[] bytes = formatter.format(BUSINESS_DATE, List.of(r1, r2));
        String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\n");
        String trailer = lines[lines.length - 1];
        String controlSum = trailer.substring(7, 7 + 15);
        assertEquals("000000000040000", controlSum);
    }

    // -----------------------------------------------------------------------
    // Test 4: status code R at offset 132
    // -----------------------------------------------------------------------

    @Test
    void format_statusCodeR_atOffset132() {
        byte[] bytes = formatter.format(BUSINESS_DATE,
                List.of(sampleRecord("TX0000000000000R001", new BigDecimal("25000"))));
        String detail = new String(bytes, StandardCharsets.UTF_8).split("\n")[1];
        assertEquals('R', detail.charAt(Zp0066Record.STATUS_CODE_OFFSET));
    }

    // -----------------------------------------------------------------------
    // Test 5: empty file
    // -----------------------------------------------------------------------

    @Test
    void format_emptyFile_headerAndTrailerOnly() {
        byte[] bytes = formatter.format(BUSINESS_DATE, List.of());
        String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\n");
        assertEquals(2, lines.length);
        assertTrue(lines[0].startsWith("ZP0066"));
        assertEquals('T', lines[1].charAt(0));
    }
}
