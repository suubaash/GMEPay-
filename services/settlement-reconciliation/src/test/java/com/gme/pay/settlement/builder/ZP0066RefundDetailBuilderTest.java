package com.gme.pay.settlement.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gme.pay.settlement.builder.AbstractZeroPayFileBuilder.BuiltFile;
import com.gme.pay.settlement.builder.DetailBuildContext.DetailRow;
import com.gme.pay.settlement.model.TransactionRecord;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fixed-width fidelity of the ZP0066 refund-detail builder: per-refund rows at placeholder widths, the
 * absolute-amount convention (num() forbids negatives), GROSS fee=0, and the empty-file contract.
 */
class ZP0066RefundDetailBuilderTest {

    private static final OffsetDateTime REFUNDED = OffsetDateTime.of(2026, 6, 26, 9, 0, 0, 0, ZoneOffset.ofHours(9));
    private static final int ROW_WIDTH = 10 + 20 + 8 + 12 + 12 + 32;   // 94
    private static final String BATCH_REF = "ZP0063-20260626-AFTERNOON";

    private static TransactionRecord refund(String ref, String merchant, long payout, char type, String rate) {
        return new TransactionRecord(3L, ref, "ZP-" + ref, merchant, BigDecimal.valueOf(payout),
                type, new BigDecimal(rate), "REFUNDED", REFUNDED, null);
    }

    private static DetailBuildContext ctx(List<TransactionRecord> txns) {
        List<DetailRow> rows = new ArrayList<>();
        for (TransactionRecord t : txns) {
            rows.add(new DetailRow(t, BATCH_REF));
        }
        return new DetailBuildContext("20260626", 1, rows);
    }

    @Test
    @DisplayName("NET refund row: exact offsets, fee_adj = round(amount*rate)")
    void goldenRowNet() {
        BuiltFile f = new ZP0066RefundDetailBuilder().build(
                ctx(List.of(refund("R1", "M001", 5000, 'N', "0.008"))));   // fee_adj = 40

        String row = f.lines().get(1);
        assertEquals(ROW_WIDTH, row.length());
        assertEquals("M001", row.substring(0, 10).trim());
        assertEquals("ZP-R1", row.substring(10, 30).trim());
        assertEquals("20260626", row.substring(30, 38));            // refund_date
        assertEquals("000000005000", row.substring(38, 50));        // refund_amount_krw (absolute)
        assertEquals("000000000040", row.substring(50, 62));        // merchant_fee_adj_amt
        assertEquals(BATCH_REF, row.substring(62, 94).trim());      // settlement_batch_ref (request batch id)
    }

    @Test
    @DisplayName("GROSS refund: merchant_fee_adj is zero")
    void goldenRowGross() {
        BuiltFile f = new ZP0066RefundDetailBuilder().build(
                ctx(List.of(refund("R2", "M002", 8000, 'G', "0.008"))));
        assertEquals("000000000000", f.lines().get(1).substring(50, 62));
    }

    @Test
    @DisplayName("negative payout is emitted as the ABSOLUTE amount (num() forbids a minus sign)")
    void absoluteAmount() {
        BuiltFile f = new ZP0066RefundDetailBuilder().build(
                ctx(List.of(refund("R3", "M001", -5000, 'N', "0"))));   // clawback intent negative
        assertEquals("000000005000", f.lines().get(1).substring(38, 50));
    }

    @Test
    @DisplayName("empty window: header + 0 data + trailer, count/total all zero")
    void emptyWindow() {
        BuiltFile f = new ZP0066RefundDetailBuilder().build(ctx(List.of()));
        assertEquals(2, f.lines().size(), "header + trailer only");
        assertEquals(0, f.recordCount());
        assertEquals(0, f.trailerTotal().signum());
        assertEquals("EOF" + "0000000000" + "0000000000000000", f.lines().get(1));
    }

    @Test
    @DisplayName("one DATA row per refund; trailer total = SUM(absolute amounts)")
    void perRefundGrain() {
        BuiltFile f = new ZP0066RefundDetailBuilder().build(ctx(List.of(
                refund("R1", "M001", 5000, 'N', "0"),
                refund("R2", "M002", 3000, 'G', "0"))));
        assertEquals(4, f.lines().size());
        assertEquals(2, f.recordCount());
        assertEquals(0, f.trailerTotal().compareTo(new BigDecimal("8000")));
    }
}
