package com.gme.pay.scheme.zeropay.batch;

import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain JUnit 5 unit tests for the ZP0011 fixed-width file format/parse round-trip.
 * No Spring context, no database, no Docker.
 */
class Zp0011RoundTripTest {

    private static final String INSTITUTION_CODE = "GMEPAY0001";
    private final Zp0011FileFormatter formatter = new Zp0011FileFormatter(INSTITUTION_CODE);
    private final Zp0011FileParser parser = new Zp0011FileParser();

    // -----------------------------------------------------------------------
    // Test 1: round-trip with two detail records
    // -----------------------------------------------------------------------

    @Test
    void roundTrip_twoRecords_parsedMatchesOriginal() {
        LocalDate businessDate = LocalDate.of(2026, 10, 14);

        Zp0011Record tx1 = new Zp0011Record(
                "TX000000000000000001",
                "ZP000000000000000001",
                "M000000001",
                "QR000000000012345678",
                businessDate, LocalTime.of(10, 30, 0),
                new BigDecimal("50000"),
                new BigDecimal("250"),
                new BigDecimal("100"),
                'D',
                "AP0000000001",
                'A'
        );

        Zp0011Record tx2 = new Zp0011Record(
                "TX000000000000000002",
                "ZP000000000000000002",
                "M000000002",
                "QR000000000098765432",
                businessDate, LocalTime.of(11, 45, 15),
                new BigDecimal("120000"),
                new BigDecimal("600"),
                new BigDecimal("200"),
                'I',
                "AP0000000002",
                'A'
        );

        byte[] fileBytes = formatter.format(businessDate, List.of(tx1, tx2));
        List<Zp0011Record> parsed = parser.parse(fileBytes);

        assertEquals(2, parsed.size());

        // Tx1 assertions
        assertEquals("TX000000000000000001", parsed.get(0).gmeTxnId());
        assertEquals("ZP000000000000000001", parsed.get(0).zeroPayTxnRef());
        assertEquals("M000000001", parsed.get(0).merchantId());
        assertEquals(new BigDecimal("50000"), parsed.get(0).payoutAmountKrw());
        assertEquals('D', parsed.get(0).partnerType());
        assertEquals('A', parsed.get(0).statusCode());

        // Tx2 assertions
        assertEquals("TX000000000000000002", parsed.get(1).gmeTxnId());
        assertEquals(new BigDecimal("120000"), parsed.get(1).payoutAmountKrw());
        assertEquals('I', parsed.get(1).partnerType());
    }

    // -----------------------------------------------------------------------
    // Test 2: header contains ZP0011 at position 0..5
    // -----------------------------------------------------------------------

    @Test
    void format_headerStartsWithZP0011() {
        LocalDate businessDate = LocalDate.of(2026, 10, 14);

        Zp0011Record tx = new Zp0011Record(
                "TX000000000000000001",
                "ZP000000000000000001",
                "M000000001",
                "QR000000000012345678",
                businessDate, LocalTime.of(9, 0, 0),
                new BigDecimal("50000"),
                new BigDecimal("250"),
                new BigDecimal("100"),
                'D', "AP0000000001", 'A'
        );

        byte[] fileBytes = formatter.format(businessDate, List.of(tx));
        String firstLine = new String(fileBytes).split("\n")[0];

        assertEquals("ZP0011", firstLine.substring(0, 6));
        assertEquals("20261014", firstLine.substring(6, 14));
    }

    // -----------------------------------------------------------------------
    // Test 3: payout_amount_krw field is NUM(12) zero-padded
    // -----------------------------------------------------------------------

    @Test
    void format_payoutAmountKrwIsZeroPaddedNum12() {
        LocalDate businessDate = LocalDate.of(2026, 10, 14);

        Zp0011Record tx = new Zp0011Record(
                "TX000000000000000001",
                "ZP000000000000000001",
                "M000000001",
                "QR000000000012345678",
                businessDate, LocalTime.of(9, 0, 0),
                new BigDecimal("50000"),
                new BigDecimal("250"),
                new BigDecimal("100"),
                'D', "AP0000000001", 'A'
        );

        byte[] fileBytes = formatter.format(businessDate, List.of(tx));
        String[] lines = new String(fileBytes).split("\n");
        String detailLine = lines[1]; // index 0 = header, index 1 = first detail

        // payout_amount_krw occupies offset 85..96 (12 chars)
        String amountField = detailLine.substring(
                Zp0011Record.PAYOUT_AMT_OFFSET,
                Zp0011Record.PAYOUT_AMT_OFFSET + Zp0011Record.PAYOUT_AMT_LEN);

        assertEquals("000000050000", amountField);
    }

    // -----------------------------------------------------------------------
    // Test 4: trailer control_sum equals sum of all detail payout amounts
    // -----------------------------------------------------------------------

    @Test
    void format_trailerControlSumEqualsSum() {
        LocalDate businessDate = LocalDate.of(2026, 10, 14);

        Zp0011Record tx1 = new Zp0011Record(
                "TX000000000000000001", "ZP000000000000000001",
                "M000000001", "QR000000000012345678",
                businessDate, LocalTime.of(9, 0, 0),
                new BigDecimal("50000"), new BigDecimal("250"), new BigDecimal("100"),
                'D', "AP0000000001", 'A'
        );
        Zp0011Record tx2 = new Zp0011Record(
                "TX000000000000000002", "ZP000000000000000002",
                "M000000002", "QR000000000098765432",
                businessDate, LocalTime.of(10, 0, 0),
                new BigDecimal("120000"), new BigDecimal("600"), new BigDecimal("200"),
                'I', "AP0000000002", 'A'
        );

        byte[] fileBytes = formatter.format(businessDate, List.of(tx1, tx2));
        String[] lines = new String(fileBytes).split("\n");
        String trailerLine = lines[lines.length - 1];

        // control sum is at offset 7..21 (15 chars) in trailer
        String controlSumField = trailerLine.substring(
                Zp0011TrailerRecord.CONTROL_SUM_OFFSET,
                Zp0011TrailerRecord.CONTROL_SUM_OFFSET + Zp0011TrailerRecord.CONTROL_SUM_LEN);

        // 50000 + 120000 = 170000, padded to 15 digits
        assertEquals("000000000170000", controlSumField);
    }

    // -----------------------------------------------------------------------
    // Test 5: zero-transaction day produces header + trailer only
    // -----------------------------------------------------------------------

    @Test
    void format_zeroTransactions_headerAndTrailerOnly() {
        LocalDate businessDate = LocalDate.of(2026, 10, 14);
        byte[] fileBytes = formatter.format(businessDate, List.of());
        String[] lines = new String(fileBytes).split("\n");

        // Header + Trailer = 2 lines
        assertEquals(2, lines.length);
        assertTrue(lines[0].startsWith("ZP0011"));
        assertEquals('T', lines[1].charAt(0));
    }

    // -----------------------------------------------------------------------
    // Test 6: idempotency — same inputs produce byte-equal output
    // -----------------------------------------------------------------------

    @Test
    void format_idempotent_sameInputsProduceSameBytes() {
        LocalDate businessDate = LocalDate.of(2026, 10, 14);

        Zp0011Record tx = new Zp0011Record(
                "TX000000000000000001", "ZP000000000000000001",
                "M000000001", "QR000000000012345678",
                businessDate, LocalTime.of(9, 0, 0),
                new BigDecimal("50000"), new BigDecimal("250"), new BigDecimal("100"),
                'D', "AP0000000001", 'A'
        );

        byte[] first  = formatter.format(businessDate, List.of(tx));
        byte[] second = formatter.format(businessDate, List.of(tx));

        assertArrayEquals(first, second);
    }

    // -----------------------------------------------------------------------
    // Test 7: parse rejects mismatched record count
    // -----------------------------------------------------------------------

    @Test
    void parse_mismatchedRecordCount_throwsValidationError() {
        // Header claims 1 record but no detail lines
        String fakeFile = "ZP001120261014GMEPAY0001" + "000001" + "000000000050000\n"
                + "T000001000000000050000";
        // (no detail line between header and trailer -> actual count = 0, header says 1)
        ApiException ex = assertThrows(ApiException.class,
                () -> parser.parse(fakeFile.getBytes()));
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.errorCode());
    }

    // -----------------------------------------------------------------------
    // Test 8: parse rejects wrong file type in header
    // -----------------------------------------------------------------------

    @Test
    void parse_wrongFileType_throwsValidationError() {
        // ZP0012 header instead of ZP0011
        String fakeFile = "ZP001220261014GMEPAY0001" + "000000" + "000000000000000\n"
                + "T000000000000000000000";
        ApiException ex = assertThrows(ApiException.class,
                () -> parser.parse(fakeFile.getBytes()));
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.errorCode());
        assertTrue(ex.getMessage().contains("ZP0012"));
    }
}
