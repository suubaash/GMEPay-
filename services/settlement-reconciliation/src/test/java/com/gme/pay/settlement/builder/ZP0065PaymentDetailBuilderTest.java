package com.gme.pay.settlement.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gme.pay.settlement.builder.AbstractZeroPayFileBuilder.BuiltFile;
import com.gme.pay.settlement.builder.DetailBuildContext.DetailRow;
import com.gme.pay.settlement.model.TransactionRecord;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fixed-width / EUC-KR fidelity of the ZP0065 payment-detail builder: one DATA row per transaction, exact
 * field offsets at the (IDD-pending) placeholder widths, fee rounding, charset + checksum, and overflow.
 */
class ZP0065PaymentDetailBuilderTest {

    private static final Charset EUC_KR = Charset.forName("EUC-KR");
    // KST 2026-06-26 14:30:00 → txn_date 20260626, txn_time 143000.
    private static final OffsetDateTime APPROVED = OffsetDateTime.of(2026, 6, 26, 14, 30, 0, 0, ZoneOffset.ofHours(9));
    private static final int ROW_WIDTH = 10 + 20 + 8 + 6 + 12 + 12 + 10 + 1 + 32;   // 111
    private static final String BATCH_REF = "ZP0061-20260626-MORNING";

    private static TransactionRecord txn(String ref, String merchant, long payout, char type,
                                         String rate, OffsetDateTime approvedAt) {
        return new TransactionRecord(1L, ref, "ZP-" + ref, merchant, BigDecimal.valueOf(payout),
                type, new BigDecimal(rate), "APPROVED", approvedAt, null);
    }

    private static DetailBuildContext ctx(List<TransactionRecord> txns) {
        List<DetailRow> rows = new ArrayList<>();
        for (TransactionRecord t : txns) {
            rows.add(new DetailRow(t, BATCH_REF));
        }
        return new DetailBuildContext("20260626", 1, rows);
    }

    @Test
    @DisplayName("NET payment row: exact field offsets, fee = round(payout*rate), partner_type D")
    void goldenRowNet() {
        BuiltFile f = new ZP0065PaymentDetailBuilder().build(
                ctx(List.of(txn("T1", "M001", 35000, 'N', "0.008", APPROVED))));   // fee = 280

        String row = f.lines().get(1);
        assertEquals(ROW_WIDTH, row.length());
        assertEquals("M001", row.substring(0, 10).trim());
        assertEquals("ZP-T1", row.substring(10, 30).trim());
        assertEquals("20260626", row.substring(30, 38));            // txn_date
        assertEquals("143000", row.substring(38, 44));              // txn_time (KST HHmmss)
        assertEquals("000000035000", row.substring(44, 56));        // payout_amount_krw
        assertEquals("000000000280", row.substring(56, 68));        // merchant_fee_amt = 35000*0.008
        assertEquals("0000000000", row.substring(68, 78));          // van_fee_amt placeholder
        assertEquals("D", row.substring(78, 79));                   // partner_type (NET=domestic)
        assertEquals(BATCH_REF, row.substring(79, 111).trim());     // settlement_batch_ref (request batch id)
    }

    @Test
    @DisplayName("GROSS payment row: partner_type I, merchant_fee zero even with a non-zero rate")
    void goldenRowGross() {
        BuiltFile f = new ZP0065PaymentDetailBuilder().build(
                ctx(List.of(txn("T2", "M002", 50000, 'G', "0.008", APPROVED))));

        String row = f.lines().get(1);
        assertEquals("000000000000", row.substring(56, 68), "GROSS books no merchant fee");
        assertEquals("I", row.substring(78, 79), "GROSS=international");
    }

    @Test
    @DisplayName("header = ZP0065+YYYYMMDD+seq(3); trailer EOF+count(10)+total(16) = SUM(payout)")
    void headerAndTrailer() {
        BuiltFile f = new ZP0065PaymentDetailBuilder().build(
                ctx(List.of(txn("T1", "M001", 35000, 'N', "0", APPROVED))));

        assertEquals("ZP0065" + "20260626" + "001", f.lines().get(0));
        assertEquals("EOF" + "0000000001" + "0000000000035000", f.lines().get(f.lines().size() - 1));
        assertEquals(1, f.recordCount());
        assertEquals(0, f.trailerTotal().compareTo(new BigDecimal("35000")));
    }

    @Test
    @DisplayName("one DATA row per TRANSACTION (two txns for the same merchant → two rows)")
    void perTransactionGrain() {
        BuiltFile f = new ZP0065PaymentDetailBuilder().build(ctx(List.of(
                txn("T1", "M001", 10000, 'N', "0", APPROVED),
                txn("T2", "M001", 20000, 'N', "0", APPROVED))));

        assertEquals(4, f.lines().size(), "header + 2 data + trailer");
        assertEquals(2, f.recordCount());
        assertEquals(0, f.trailerTotal().compareTo(new BigDecimal("30000")));
    }

    @Test
    @DisplayName("merchant_fee rounds HALF_UP: 12345*0.008 = 98.76 → 99")
    void feeRoundingHalfUp() {
        BuiltFile f = new ZP0065PaymentDetailBuilder().build(
                ctx(List.of(txn("T1", "M001", 12345, 'N', "0.008", APPROVED))));
        assertEquals("000000000099", f.lines().get(1).substring(56, 68));
    }

    @Test
    @DisplayName("EUC-KR bytes + 64-hex lowercase checksum")
    void charsetAndChecksum() {
        BuiltFile f = new ZP0065PaymentDetailBuilder().build(
                ctx(List.of(txn("T1", "M001", 35000, 'N', "0.008", APPROVED))));
        assertEquals(new String(String.join("\n", f.lines()).getBytes(EUC_KR), EUC_KR),
                new String(f.bytes(), EUC_KR));
        assertTrue(f.checksum().matches("[0-9a-f]{64}"), "lowercase 64-hex SHA-256");
    }

    @Test
    @DisplayName("null approval instant → txn_time placeholder 000000, txn_date falls back to business date")
    void nullApprovedAtPlaceholders() {
        BuiltFile f = new ZP0065PaymentDetailBuilder().build(
                ctx(List.of(txn("T1", "M001", 35000, 'N', "0", null))));
        String row = f.lines().get(1);
        assertEquals("20260626", row.substring(30, 38), "txn_date falls back to ctx business date");
        assertEquals("000000", row.substring(38, 44), "txn_time placeholder when no approval instant");
    }

    @Test
    @DisplayName("field overflow throws (merchant_id > 10 bytes)")
    void overflowThrows() {
        assertThrows(IllegalStateException.class, () -> new ZP0065PaymentDetailBuilder().build(
                ctx(List.of(txn("T1", "MERCHANT-TOO-LONG", 100, 'N', "0", APPROVED)))));
    }
}
