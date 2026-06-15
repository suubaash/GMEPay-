package com.gme.pay.scheme.zeropay.batch;

import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ZP0021 refund-result fixed-width file format.
 * No Spring context, no database, no Docker.
 */
class Zp0021RoundTripTest {

    private static final String INSTITUTION_CODE = "GMEPAY0001";
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 10, 14);

    private final Zp0021FileFormatter formatter = new Zp0021FileFormatter(INSTITUTION_CODE);

    private Zp0021Record sampleRecord(String gmeTxnId, BigDecimal amount) {
        return new Zp0021Record(
                gmeTxnId,
                "ZP00000000000000REFD",
                "M000000001",
                "QR000000000012345678",
                BUSINESS_DATE,
                LocalTime.of(14, 30, 0),
                amount,
                new BigDecimal("500"),
                new BigDecimal("150"),
                'D',
                "AP0000000001",
                'R'
        );
    }

    // -----------------------------------------------------------------------
    // Test 1: golden-row — header starts ZP0021
    // -----------------------------------------------------------------------

    @Test
    void format_headerStartsWithZP0021() {
        byte[] bytes = formatter.format(BUSINESS_DATE, List.of(sampleRecord("TX0000000000000R001",
                new BigDecimal("30000"))));
        String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\n");
        assertEquals("ZP0021", lines[0].substring(0, 6));
        assertEquals("20261014", lines[0].substring(6, 14));
    }

    // -----------------------------------------------------------------------
    // Test 2: detail record length is 133
    // -----------------------------------------------------------------------

    @Test
    void format_detailRecordLengthIs133() {
        byte[] bytes = formatter.format(BUSINESS_DATE,
                List.of(sampleRecord("TX0000000000000R001", new BigDecimal("30000"))));
        String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\n");
        // lines[0] = header, lines[1] = detail, lines[2] = trailer
        assertEquals(Zp0021Record.RECORD_LENGTH, lines[1].length());
    }

    // -----------------------------------------------------------------------
    // Test 3: refund amount is NUM(12) zero-padded at offset 85
    // -----------------------------------------------------------------------

    @Test
    void format_refundAmountZeroPaddedNum12() {
        byte[] bytes = formatter.format(BUSINESS_DATE,
                List.of(sampleRecord("TX0000000000000R001", new BigDecimal("30000"))));
        String detail = new String(bytes, StandardCharsets.UTF_8).split("\n")[1];
        String amtField = detail.substring(
                Zp0021Record.REFUND_AMT_OFFSET,
                Zp0021Record.REFUND_AMT_OFFSET + Zp0021Record.REFUND_AMT_LEN);
        assertEquals("000000030000", amtField);
    }

    // -----------------------------------------------------------------------
    // Test 4: trailer control sum = sum of all refund amounts
    // -----------------------------------------------------------------------

    @Test
    void format_trailerControlSumIsRefundSum() {
        Zp0021Record r1 = sampleRecord("TX0000000000000R001", new BigDecimal("30000"));
        Zp0021Record r2 = sampleRecord("TX0000000000000R002", new BigDecimal("15000"));
        byte[] bytes = formatter.format(BUSINESS_DATE, List.of(r1, r2));
        String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\n");
        String trailer = lines[lines.length - 1];
        // controlSum at offset 7, length 15
        String controlSum = trailer.substring(7, 7 + 15);
        assertEquals("000000000045000", controlSum);
    }

    // -----------------------------------------------------------------------
    // Test 5: empty file — header + trailer only
    // -----------------------------------------------------------------------

    @Test
    void format_emptyFile_headerAndTrailerOnly() {
        byte[] bytes = formatter.format(BUSINESS_DATE, List.of());
        String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\n");
        assertEquals(2, lines.length);
        assertTrue(lines[0].startsWith("ZP0021"));
        assertEquals('T', lines[1].charAt(0));
    }

    // -----------------------------------------------------------------------
    // Test 6: status code 'R' is preserved at offset 132
    // -----------------------------------------------------------------------

    @Test
    void format_statusCodeR_atOffset132() {
        byte[] bytes = formatter.format(BUSINESS_DATE,
                List.of(sampleRecord("TX0000000000000R001", new BigDecimal("30000"))));
        String detail = new String(bytes, StandardCharsets.UTF_8).split("\n")[1];
        assertEquals('R', detail.charAt(Zp0021Record.STATUS_CODE_OFFSET));
    }

    // -----------------------------------------------------------------------
    // Test 7: numeric overflow throws IllegalArgumentException
    // -----------------------------------------------------------------------

    @Test
    void format_amountExceedsFieldWidth_throwsIllegalArgument() {
        Zp0021Record badRecord = new Zp0021Record(
                "TX0000000000000R001", "ZP00000000000000REFD",
                "M000000001", "QR000000000012345678",
                BUSINESS_DATE, LocalTime.of(14, 30, 0),
                new BigDecimal("9999999999999"),   // 13 digits > NUM(12)
                BigDecimal.ZERO, BigDecimal.ZERO,
                'D', "AP0000000001", 'R'
        );
        assertThrows(IllegalArgumentException.class,
                () -> formatter.format(BUSINESS_DATE, List.of(badRecord)));
    }
}
