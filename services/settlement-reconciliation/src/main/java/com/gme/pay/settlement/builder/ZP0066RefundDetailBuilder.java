package com.gme.pay.settlement.builder;

import com.gme.pay.settlement.model.TransactionRecord;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * ZP0066 refund-detail file (GME → ZeroPay, ~22:00 KST): one DATA row per refund for the settlement date,
 * so ZeroPay can reconcile refund claw-backs against the ZP0061/ZP0063 summary. A day with no refunds
 * still emits a valid header + 0 DATA rows + trailer (empty-file contract). Reuses the
 * {@link AbstractZeroPayFileBuilder} contract; consumes a per-transaction {@link DetailBuildContext}.
 *
 * <p>DATA layout (placeholder widths — IDD-pending, isolated as constants): merchant_id AN(10) ·
 * original_zeropay_txn_ref AN(20) · refund_date(8) · refund_amount_krw N(12) · merchant_fee_adj_amt N(12)
 * · settlement_batch_ref AN(20). KRW fields are zero-padded integers (scale 0).
 *
 * <p>The {@code settlement_batch_ref} is the id of the ZP0061/ZP0063 request batch the claw-back was
 * applied in, supplied per-row via {@link DetailBuildContext.DetailRow}. The job sources these rows from
 * the negative (claw-back) settlement_lines of the day's request batches, so ZP0066 ties out to exactly
 * the refunds the aggregate net deducted (no divergence).
 *
 * <p><b>NOT TRANSMIT-READY — structural builder only.</b> Remaining gaps (flagged placeholders):
 * <ul>
 *   <li><b>refund_amount_krw sign</b> — {@code num()} forbids negatives, so the ABSOLUTE KRW amount is
 *       emitted; the claw-back sign lives in the aggregate {@code settlement_lines}, not this report. The
 *       file-level sign convention (leading minus vs absolute) is IDD-pending.</li>
 *   <li><b>original_zeropay_txn_ref</b> — sourced from {@code schemeRef} (closest available); confirm it
 *       carries the ORIGINAL payment's ref, not a refund-specific one.</li>
 * </ul>
 */
public class ZP0066RefundDetailBuilder extends AbstractZeroPayFileBuilder {

    static final String FILE_CODE = "ZP0066";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // NB: explicit yyyyMMdd, NOT BASIC_ISO_DATE — the latter appends the zone offset ("+0900") when
    // formatting a zoned/offset temporal, which would corrupt the fixed-width layout.
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");   // YYYYMMDD

    // IDD-pending placeholder widths (service-backlog 7.1-T13), isolated for one-place correction.
    static final int W_MERCHANT_ID = 10;
    static final int W_ORIG_TXN_REF = 20;
    static final int W_AMOUNT = 12;
    static final int W_FEE = 12;
    // Wide enough for the internal request batchId placeholder ("ZP00NN-YYYYMMDD-WINDOW", ≤25 chars). The
    // ZeroPay-facing ref encoding + width are IDD-pending; the value emitted is the correct tie-out anchor.
    static final int W_BATCH_REF = 32;

    @Override
    protected String fileCode() {
        return FILE_CODE;
    }

    /** Unsupported — ZP0066 is per-transaction; call {@link #build(DetailBuildContext)}. */
    @Override
    public BuiltFile build(BuildContext ctx) {
        throw new UnsupportedOperationException("ZP0066 is per-transaction; call build(DetailBuildContext)");
    }

    public BuiltFile build(DetailBuildContext ctx) {
        List<String> lines = new ArrayList<>();
        lines.add(header(FILE_CODE, ctx.yyyymmdd(), ctx.sequence()));

        BigDecimal total = BigDecimal.ZERO;
        for (DetailBuildContext.DetailRow row : ctx.rows()) {
            TransactionRecord t = row.txn();
            // num() forbids negatives → emit the ABSOLUTE KRW amount; the claw-back sign lives in the
            // aggregate settlement_lines, not this report (file sign convention is IDD-pending).
            BigDecimal amt = nz(t.targetPayoutKrw()).abs().setScale(0, RoundingMode.HALF_UP);
            BigDecimal feeAdj = t.isNet()
                    ? nz(t.targetPayoutKrw()).abs().multiply(nz(t.merchantFeeRate())).setScale(0, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            lines.add(
                    an(t.merchantId(), W_MERCHANT_ID)
                            + an(t.schemeRef(), W_ORIG_TXN_REF)           // original payment scheme ref (closest avail.)
                            + refundDate(t, ctx)
                            + num(amt, W_AMOUNT)
                            + num(feeAdj, W_FEE)
                            + an(row.settlementBatchRef(), W_BATCH_REF)); // ZP0061/ZP0063 request batch (tie-out key)
            total = total.add(amt);
        }
        lines.add(trailer(ctx.rows().size(), total));

        byte[] bytes = String.join("\n", lines).getBytes(EUC_KR);
        return new BuiltFile(FILE_CODE, lines, sha256Hex(lines), bytes, ctx.rows().size(), total);
    }

    /** Refund date YYYYMMDD in KST from the approval instant; falls back to the business date if null. */
    private static String refundDate(TransactionRecord t, DetailBuildContext ctx) {
        return t.completedAt() == null
                ? ctx.yyyymmdd()
                : t.completedAt().atZoneSameInstant(KST).format(DATE);
    }
}
