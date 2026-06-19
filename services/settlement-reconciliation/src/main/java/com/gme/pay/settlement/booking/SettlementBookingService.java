package com.gme.pay.settlement.booking;

import com.gme.pay.money.BookedAmount;
import com.gme.pay.money.CurrencyScale;
import com.gme.pay.money.SettlementRounding;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Service;

/**
 * Books a per-partner settlement liability under the partner's OWN configured rounding mode
 * (Spec Addendum 001, approved 2026-06-08 — authoritative over the fixed-HALF_UP text in WBS 7.1 /
 * the ROUND_DOWN text in WBS 7.6). This is the single home for the Addendum-001 rounding on the
 * OUTBOUND path; it replaces the hardcoded {@code RoundingMode.HALF_UP} in the inbound calculators.
 *
 * <p>Rounding is applied ONCE, at the per-partner per-window OUTPUT amount — never on intermediates
 * (gross/fee sums are accumulated at full precision by the caller and passed in as {@code precise}).
 * The {@code residual = precise - booked} is the rounding gain/loss to post to the REVENUE_ROUNDING
 * ledger (gain when &gt;0, loss when &lt;0) so the partner is booked exactly and the internal books stay
 * balanced — the cross-service ledger post is wired separately (revenue-ledger, Addendum §pending).
 */
@Service
public class SettlementBookingService {

    /**
     * Book {@code precise} as the partner's settlement liability.
     *
     * @param settleCcy      partner settlement currency (drives the minor-unit scale; KRW/JPY/VND = 0)
     * @param mode           partner's configured rounding mode; {@code null} ⇒ HALF_UP (Addendum default)
     * @param precise        full-precision settlement amount (NET = gross − fee; GROSS = gross)
     * @param settlementType 'N' (NET/domestic) or 'G' (GROSS/international)
     * @return the booked amount + residual + the scale/mode actually used
     * @throws NegativeSettlementAmountException if a GROSS settlement nets below zero (7.6-T04)
     */
    public BookedAmount book(String settleCcy, RoundingMode mode, BigDecimal precise, char settlementType) {
        RoundingMode effectiveMode = (mode == null) ? RoundingMode.HALF_UP : mode;
        int scale = CurrencyScale.scale(settleCcy);
        BookedAmount booked = SettlementRounding.book(precise, scale, effectiveMode);
        if (settlementType == 'G' && booked.booked().signum() < 0) {
            throw new NegativeSettlementAmountException(
                    "GROSS settlement net < 0 (refunds exceed payments): " + booked.booked() + " " + settleCcy);
        }
        return booked;
    }
}
