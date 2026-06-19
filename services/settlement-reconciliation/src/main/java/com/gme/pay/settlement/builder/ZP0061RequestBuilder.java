package com.gme.pay.settlement.builder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * ZP0061 (morning, ~05:00 KST) / ZP0063 (afternoon, ~14:00 KST — identical layout) settlement-request
 * file: one DATA row per merchant. The same instance serves both windows via the {@code fileCode} flag.
 *
 * <p>Per merchant the {@code netSettlementAmount} is the Addendum-001 BOOKED amount under the partner's
 * rounding mode (computed upstream by {@code SettlementBookingService}). NET ('N'): net = gross − fee
 * (+ refunds). GROSS ('G'): merchant_fee_total = 0, net = gross. KRW fields are zero-padded integer
 * strings with no decimal point (KRW scale = 0).
 *
 * <p>DATA layout (7.1-T09, widths IDD-pending): merchant_id AN(10) · settlement_date(8) · gross_count
 * N(6) · gross_amount N(14) · refund_count N(6) · refund_amount N(14) · merchant_fee_total N(12) ·
 * net_settlement_amount N(14) · settlement_type(1).
 */
public class ZP0061RequestBuilder extends AbstractZeroPayFileBuilder {

    private final String fileCode; // "ZP0061" (morning) or "ZP0063" (afternoon)

    public ZP0061RequestBuilder(String fileCode) {
        this.fileCode = fileCode;
    }

    @Override
    protected String fileCode() {
        return fileCode;
    }

    @Override
    public BuiltFile build(BuildContext ctx) {
        List<String> lines = new ArrayList<>();
        lines.add(header(fileCode, ctx.yyyymmdd(), ctx.sequence()));

        BigDecimal netTotal = BigDecimal.ZERO;
        for (BuildContext.MerchantRow m : ctx.rows()) {
            lines.add(
                    an(m.merchantId(), 10)
                            + ctx.yyyymmdd()
                            + num(BigDecimal.valueOf(m.grossTxnCount()), 6)
                            + num(m.grossTxnAmount(), 14)
                            + num(BigDecimal.valueOf(m.refundCount()), 6)
                            + num(m.refundAmount(), 14)
                            + num(m.merchantFeeTotal(), 12)
                            + num(m.netSettlementAmount(), 14)   // booked (Addendum-001)
                            + m.settlementType());                // 'N' | 'G'
            netTotal = netTotal.add(m.netSettlementAmount());
        }
        lines.add(trailer(ctx.rows().size(), netTotal));

        byte[] bytes = String.join("\n", lines).getBytes(EUC_KR);
        return new BuiltFile(fileCode, lines, sha256Hex(lines), bytes, ctx.rows().size(), netTotal);
    }
}
