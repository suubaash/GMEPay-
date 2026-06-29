package com.gme.pay.settlement.builder;

import com.gme.pay.settlement.model.TransactionRecord;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * ZP0065 payment-detail file (GME → ZeroPay, ~22:00 KST): one DATA row per APPROVED payment settled that
 * day, so ZeroPay can reconcile the per-transaction detail against the ZP0061/ZP0063 summary it already
 * received. Reuses the {@link AbstractZeroPayFileBuilder} HEADER/TRAILER/{@code num()}/{@code an()}/EUC-KR
 * /checksum contract; consumes a per-transaction {@link DetailBuildContext} (the abstract per-merchant
 * {@link #build(BuildContext)} is intentionally unsupported here).
 *
 * <p>DATA layout (placeholder widths — IDD-pending, isolated as constants, same convention as
 * {@link ZP0061RequestBuilder}): merchant_id AN(10) · zeropay_txn_ref AN(20) · txn_date(8) · txn_time(6)
 * · payout_amount_krw N(12) · merchant_fee_amt N(12) · van_fee_amt N(10) · partner_type(1) ·
 * settlement_batch_ref AN(20). KRW fields are zero-padded integers (scale 0). merchant_fee_amt = Σ rounded
 * payout×rate for NET ('N'); 0 for GROSS ('G').
 *
 * <p>The {@code settlement_batch_ref} is the id of the ZP0061/ZP0063 request batch the txn was settled in,
 * supplied per-row via {@link DetailBuildContext.DetailRow} (the job resolves it from the request batch's
 * settlement_lines). Sourcing the detail rows from those same lines ties ZP0065 SUM(payout) out to the
 * ZP0061+ZP0063 gross.
 *
 * <p><b>NOT TRANSMIT-READY — structural builder only.</b> Remaining gaps (emitted as flagged placeholders,
 * never invented data):
 * <ul>
 *   <li><b>van_fee_amt</b> — no source field or documented formula; ships ZERO.</li>
 *   <li><b>txn_time</b> — only the scheme approval instant ({@code completedAt} = approvedAt) exists; its
 *       HHMMSS is used as a proxy ({@code 000000} when null). Confirm against the final IDD.</li>
 * </ul>
 * Field widths are also IDD-pending (note {@code ZP0062}'s merchant_id is 16 vs the 10 specced here — to
 * be reconciled against the final ZeroPay IDD).
 */
public class ZP0065PaymentDetailBuilder extends AbstractZeroPayFileBuilder {

    static final String FILE_CODE = "ZP0065";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // NB: explicit yyyyMMdd, NOT BASIC_ISO_DATE — the latter appends the zone offset ("+0900") when
    // formatting a zoned/offset temporal, which would corrupt the fixed-width layout.
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");   // YYYYMMDD
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmmss");

    // IDD-pending placeholder widths (service-backlog 7.1-T12), isolated for one-place correction.
    static final int W_MERCHANT_ID = 10;
    static final int W_TXN_REF = 20;
    static final int W_AMOUNT = 12;
    static final int W_FEE = 12;
    static final int W_VAN_FEE = 10;
    static final int W_PARTNER_TYPE = 1;
    // Wide enough for the internal request batchId placeholder ("ZP00NN-YYYYMMDD-WINDOW", ≤25 chars). The
    // ZeroPay-facing ref encoding + width are IDD-pending; the value emitted is the correct tie-out anchor.
    static final int W_BATCH_REF = 32;

    @Override
    protected String fileCode() {
        return FILE_CODE;
    }

    /** Unsupported — ZP0065 is per-transaction; call {@link #build(DetailBuildContext)}. */
    @Override
    public BuiltFile build(BuildContext ctx) {
        throw new UnsupportedOperationException("ZP0065 is per-transaction; call build(DetailBuildContext)");
    }

    public BuiltFile build(DetailBuildContext ctx) {
        List<String> lines = new ArrayList<>();
        lines.add(header(FILE_CODE, ctx.yyyymmdd(), ctx.sequence()));

        BigDecimal total = BigDecimal.ZERO;
        for (DetailBuildContext.DetailRow row : ctx.rows()) {
            TransactionRecord t = row.txn();
            BigDecimal payout = nz(t.targetPayoutKrw()).setScale(0, RoundingMode.HALF_UP);
            BigDecimal fee = t.isNet()
                    ? nz(t.targetPayoutKrw()).multiply(nz(t.merchantFeeRate())).setScale(0, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            lines.add(
                    an(t.merchantId(), W_MERCHANT_ID)
                            + an(t.schemeRef(), W_TXN_REF)
                            + txnDate(t, ctx)
                            + txnTime(t)
                            + num(payout, W_AMOUNT)
                            + num(fee, W_FEE)
                            + num(BigDecimal.ZERO, W_VAN_FEE)              // van_fee_amt: MISSING source — placeholder
                            + an(t.isNet() ? "D" : "I", W_PARTNER_TYPE)   // domestic(NET) / international(GROSS)
                            + an(row.settlementBatchRef(), W_BATCH_REF)); // ZP0061/ZP0063 request batch (tie-out key)
            total = total.add(payout);
        }
        lines.add(trailer(ctx.rows().size(), total));

        byte[] bytes = String.join("\n", lines).getBytes(EUC_KR);
        return new BuiltFile(FILE_CODE, lines, sha256Hex(lines), bytes, ctx.rows().size(), total);
    }

    /** Transaction date YYYYMMDD in KST from the approval instant; falls back to the business date if null. */
    private static String txnDate(TransactionRecord t, DetailBuildContext ctx) {
        return t.completedAt() == null
                ? ctx.yyyymmdd()
                : t.completedAt().atZoneSameInstant(KST).format(DATE);
    }

    /** Transaction time HHMMSS in KST from the approval instant; placeholder zeros when null (flagged gap). */
    private static String txnTime(TransactionRecord t) {
        return t.completedAt() == null
                ? "0".repeat(6)
                : t.completedAt().atZoneSameInstant(KST).format(TIME);
    }
}
