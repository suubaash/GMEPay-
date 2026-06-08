package com.gme.pay.settlement.calculator;

import com.gme.pay.settlement.model.TransactionRecord;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * Calculates the GROSS settlement amount for a list of international (settlement_type='G') transactions.
 *
 * <p>Per-merchant totals:
 * <ul>
 *   <li>grossTxnAmount     = SUM(target_payout KRW)</li>
 *   <li>merchantFeeTotal   = 0 (GME invoices merchant monthly)</li>
 *   <li>netSettlementAmount = grossTxnAmount (full payout remitted to ZeroPay)</li>
 * </ul>
 *
 * <p>FX margin fields (collection_margin_usd, payout_margin_usd) and service_charge are NOT
 * included in or subtracted from grossTxnAmount — they are retained at payment time.
 *
 * <p>The 0.21 % ZeroPay scheme share is a monthly aggregate settled via tax_invoice, not here.
 *
 * <p>Spec reference: 7.1-T06.
 */
@Component
public class GrossSettlementAmountCalculator {

    private static final int KRW_SCALE = 0;

    /**
     * Calculate the GROSS settlement summary for the given transactions.
     * All transactions must have settlement_type='G' and status='APPROVED'.
     *
     * @param transactions   list of approved GROSS transactions (may be empty)
     * @param settlementDate the settlement date for this batch window
     * @param merchantId     the merchant this summary is for
     * @return a {@link GrossSettlementSummary} — zero amounts when {@code transactions} is empty
     * @throws IllegalArgumentException if any transaction has settlement_type != 'G' or status != APPROVED
     */
    public GrossSettlementSummary calculate(
            String merchantId,
            LocalDate settlementDate,
            List<TransactionRecord> transactions) {

        if (transactions == null || transactions.isEmpty()) {
            return new GrossSettlementSummary(merchantId, settlementDate, 0,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        BigDecimal grossTxnAmount = BigDecimal.ZERO;

        for (TransactionRecord txn : transactions) {
            if (!txn.isGross()) {
                throw new IllegalArgumentException(
                        "GROSS calculator received a NET transaction: txnRef=" + txn.txnRef());
            }
            if (!txn.isApproved()) {
                throw new IllegalArgumentException(
                        "Transaction is not APPROVED: txnRef=" + txn.txnRef() + " status=" + txn.status());
            }
            grossTxnAmount = grossTxnAmount.add(txn.targetPayoutKrw());
        }

        grossTxnAmount = grossTxnAmount.setScale(KRW_SCALE, RoundingMode.HALF_UP);

        // GROSS: no fee deduction — net == gross
        return new GrossSettlementSummary(
                merchantId,
                settlementDate,
                transactions.size(),
                grossTxnAmount,
                BigDecimal.ZERO,      // merchantFeeTotal = 0 for GROSS
                grossTxnAmount);      // netSettlementAmount == grossTxnAmount
    }
}
