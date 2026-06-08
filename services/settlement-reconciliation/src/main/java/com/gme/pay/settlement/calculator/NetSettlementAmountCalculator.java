package com.gme.pay.settlement.calculator;

import com.gme.pay.settlement.model.TransactionRecord;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * Calculates the NET settlement amount for a list of domestic (settlement_type='N') transactions.
 *
 * <p>Per-merchant totals:
 * <ul>
 *   <li>grossTxnAmount   = SUM(target_payout KRW)</li>
 *   <li>merchantFeeTotal = SUM(ROUND(target_payout * fee_rate, 0, HALF_UP))</li>
 *   <li>netSettlementAmount = grossTxnAmount - merchantFeeTotal</li>
 * </ul>
 *
 * <p>KRW has 0 decimal places — all amounts are rounded HALF_UP to integer KRW.
 *
 * <p>Spec reference: 7.1-T05.
 */
@Component
public class NetSettlementAmountCalculator {

    private static final int KRW_SCALE = 0;

    /**
     * Calculate the NET settlement summary for the given transactions.
     * All transactions must have settlement_type='N' and status='APPROVED'.
     *
     * @param transactions  list of approved NET transactions (may be empty)
     * @param settlementDate the settlement date for this batch window
     * @param merchantId     the merchant this summary is for
     * @return a {@link NetSettlementSummary} — zero amounts when {@code transactions} is empty
     * @throws IllegalArgumentException if any transaction has settlement_type != 'N' or status != APPROVED
     */
    public NetSettlementSummary calculate(
            String merchantId,
            LocalDate settlementDate,
            List<TransactionRecord> transactions) {

        if (transactions == null || transactions.isEmpty()) {
            return new NetSettlementSummary(merchantId, settlementDate, 0,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        BigDecimal grossTxnAmount = BigDecimal.ZERO;
        BigDecimal merchantFeeTotal = BigDecimal.ZERO;

        for (TransactionRecord txn : transactions) {
            if (!txn.isNet()) {
                throw new IllegalArgumentException(
                        "NET calculator received a GROSS transaction: txnRef=" + txn.txnRef());
            }
            if (!txn.isApproved()) {
                throw new IllegalArgumentException(
                        "Transaction is not APPROVED: txnRef=" + txn.txnRef() + " status=" + txn.status());
            }

            BigDecimal payout = txn.targetPayoutKrw();
            grossTxnAmount = grossTxnAmount.add(payout);

            // per-transaction fee: ROUND(target_payout * fee_rate, 0, HALF_UP)
            BigDecimal fee = payout.multiply(txn.merchantFeeRate())
                    .setScale(KRW_SCALE, RoundingMode.HALF_UP);
            merchantFeeTotal = merchantFeeTotal.add(fee);
        }

        // Ensure integer scale on totals
        grossTxnAmount = grossTxnAmount.setScale(KRW_SCALE, RoundingMode.HALF_UP);
        merchantFeeTotal = merchantFeeTotal.setScale(KRW_SCALE, RoundingMode.HALF_UP);
        BigDecimal netSettlementAmount = grossTxnAmount.subtract(merchantFeeTotal);

        return new NetSettlementSummary(
                merchantId,
                settlementDate,
                transactions.size(),
                grossTxnAmount,
                merchantFeeTotal,
                netSettlementAmount);
    }
}
