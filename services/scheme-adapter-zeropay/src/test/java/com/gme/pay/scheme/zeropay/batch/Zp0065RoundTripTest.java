package com.gme.pay.scheme.zeropay.batch;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ZP0065 payment-detail fixed-width file format.
 * No Spring context, no database, no Docker.
 */
class Zp0065RoundTripTest {

    private static final String INSTITUTION_CODE = "GMEPAY0001";
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 10, 14);
    private static final LocalDate SETTLEMENT_DATE = LocalDate.of(2026, 10, 15);

    private final Zp0065FileFormatter formatter = new Zp0065FileFormatter(INSTITUTION_CODE);

    private Zp0065Record sampleRecord(String gmeTxnId, BigDecimal amount) {
        return new Zp0065Record(
                gmeTxnId,
                "ZP000000000000000001",
                "M000000001",
                "QR000000000012345678",
                BUSINESS_DATE,
                LocalTime.of(21, 45, 0),
                amount,
                new BigDecimal("500"),
                new BigDecimal("150"),
                'D',
                "AP0000000001",
                'A',
                SETTLEMENT_DATE
        );
    }

    // -----------------------------------------------------------------------
    // Test 1: golden-row — header starts ZP0065
    // -----------------------------------------------------------------------

    @Test
    void format_headerStartsWithZP0065() {
        byte[] bytes = formatter.format(BUSINESS_DATE,
                List.of(sampleRecord("TX0000000000000P001", new BigDecimal("75000"))));
        String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\n");
        assertEquals("ZP0065", lines[0].substring(0, 6));
        assertEquals("20261014", lines[0].substring(6, 14));
    }

    // -----------------------------------------------------------------------
    // Test 2: detail record length is 150
    // -----------------------------------------------------------------------

    @Test
    void format_detailRecordLengthIs150() {
        byte[] bytes = formatter.format(BUSINESS_DATE,
                List.of(sampleRecord("TX0000000000000P001", new BigDecimal("75000"))));
        String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\n");
        assertEquals(Zp0065Record.RECORD_LENGTH, lines[1].length());
    }

    // -----------------------------------------------------------------------
    // Test 3: settlement date appears at offset 133 in detail line
    // -----------------------------------------------------------------------

    @Test
    void format_settlementDateAtOffset133() {
        byte[] bytes = formatter.format(BUSINESS_DATE,
                List.of(sampleRecord("TX0000000000000P001", new BigDecimal("75000"))));
        String detail = new String(bytes, StandardCharsets.UTF_8).split("\n")[1];
        String settlementDateField = detail.substring(
                Zp0065Record.SETTLEMENT_DATE_OFFSET,
                Zp0065Record.SETTLEMENT_DATE_OFFSET + Zp0065Record.SETTLEMENT_DATE_LEN);
        assertEquals("20261015", settlementDateField);
    }

    // -----------------------------------------------------------------------
    // Test 4: trailer control sum = sum of payout amounts
    // -----------------------------------------------------------------------

    @Test
    void format_trailerControlSumIsPayoutSum() {
        Zp0065Record r1 = sampleRecord("TX0000000000000P001", new BigDecimal("75000"));
        Zp0065Record r2 = sampleRecord("TX0000000000000P002", new BigDecimal("125000"));
        byte[] bytes = formatter.format(BUSINESS_DATE, List.of(r1, r2));
        String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\n");
        String trailer = lines[lines.length - 1];
        String controlSum = trailer.substring(7, 7 + 15);
        assertEquals("000000000200000", controlSum);
    }

    // -----------------------------------------------------------------------
    // Test 5: empty file
    // -----------------------------------------------------------------------

    @Test
    void format_emptyFile_headerAndTrailerOnly() {
        byte[] bytes = formatter.format(BUSINESS_DATE, List.of());
        String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\n");
        assertEquals(2, lines.length);
        assertTrue(lines[0].startsWith("ZP0065"));
        assertEquals('T', lines[1].charAt(0));
    }
}
