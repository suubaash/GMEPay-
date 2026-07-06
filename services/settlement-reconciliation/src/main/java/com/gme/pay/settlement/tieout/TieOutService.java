package com.gme.pay.settlement.tieout;

import com.gme.pay.settlement.model.TransactionRecord;
import com.gme.pay.settlement.persistence.SettlementBatchEntity;
import com.gme.pay.settlement.persistence.SettlementBatchRepository;
import com.gme.pay.settlement.persistence.SettlementLineEntity;
import com.gme.pay.settlement.persistence.SettlementLineRepository;
import com.gme.pay.settlement.port.LedgerRevenuePort;
import com.gme.pay.settlement.port.TransactionQueryPort;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Application service for GET /v1/settlements/tie-out — assembles the daily cent-for-cent
 * reconciliation across the three money views of one business date.
 *
 * <h2>Money reasoning</h2>
 *
 * <p><b>1. Transaction gross ({@code txnGrossKrw}).</b> The date's APPROVED payout total, summed
 * from TWO complementary sources because {@link TransactionQueryPort#findUnbatchedApproved}
 * deliberately returns only transactions NOT YET assigned to a settlement batch
 * ({@code settlement_batch_id IS NULL}):
 * <ul>
 *   <li>the port's unbatched APPROVED transactions (Σ {@code targetPayoutKrw}) — approved money the
 *       settlement engine has not yet picked up; plus</li>
 *   <li>the positive {@code settlement_lines} amounts of the date's persisted batches — the
 *       already-settled portion, read from this service's own authoritative snapshot (a settled
 *       payment line's {@code amount} is the txn's KRW payout at batching time).</li>
 * </ul>
 * Together they cover every approved payout exactly once: a transaction is either still unbatched
 * (source 1) or has exactly one positive line in a batch (source 2), never both.
 *
 * <p><b>2. Settlement engine internal balance ({@code settlementBalanced}).</b>
 * {@code SettlementBatchJobService} books each batch under the invariant
 * <i>net = gross − fee − refund</i>, i.e. <i>gross = net + fee + refund</i>. We recompute both
 * sides independently from persisted rows:
 * <ul>
 *   <li>left: Σ {@code net_settlement_amount} + Σ {@code merchant_fee_total} (batch headers) +
 *       Σ |negative line amounts| (refund clawbacks, {@code settledRefundKrw});</li>
 *   <li>right: Σ positive line amounts (the batches' settled gross).</li>
 * </ul>
 * Equality is checked with {@link BigDecimal#compareTo} (value equality regardless of scale —
 * headers persist at scale 4, lines at scale 8), so a single missing/extra won breaks the flag.
 *
 * <p><b>3. Ledger agreement ({@code ledgerDeltaKrw}).</b> Every KRW of merchant fee the engine
 * withheld must surface as recognised fee revenue in revenue-ledger:
 * {@code delta = settledFeeKrw − ledgerFeeKrw}, expected 0. The ledger leg is optional
 * ({@link LedgerRevenuePort} returns {@code null} when the client is disabled/unreachable):
 * without it the report still proves the engine's internal balance, and {@code tiedOut} is driven
 * by {@code settlementBalanced} alone.
 *
 * <p>All sums are plain BigDecimal addition — no rounding is introduced here; this service only
 * re-adds amounts that were already booked at their authoritative scale.
 */
@Service
public class TieOutService {

    private final TransactionQueryPort transactionQueryPort;
    private final SettlementBatchRepository batchRepository;
    private final SettlementLineRepository lineRepository;
    private final LedgerRevenuePort ledgerRevenuePort;

    public TieOutService(TransactionQueryPort transactionQueryPort,
                         SettlementBatchRepository batchRepository,
                         SettlementLineRepository lineRepository,
                         LedgerRevenuePort ledgerRevenuePort) {
        this.transactionQueryPort = transactionQueryPort;
        this.batchRepository = batchRepository;
        this.lineRepository = lineRepository;
        this.ledgerRevenuePort = ledgerRevenuePort;
    }

    /** Build the tie-out report for one business date. */
    public TieOutReport report(LocalDate businessDate) {
        // Source 1: approved-but-not-yet-batched payouts (port excludes batched txns by contract).
        BigDecimal unbatchedGross = transactionQueryPort.findUnbatchedApproved(businessDate).stream()
                .map(TransactionRecord::targetPayoutKrw)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Source 2: the date's persisted batches and their lines (this service's own tables).
        List<SettlementBatchEntity> batches = batchRepository.findByBusinessDate(businessDate);

        BigDecimal settledNet = BigDecimal.ZERO;
        BigDecimal settledFee = BigDecimal.ZERO;
        BigDecimal linesPositive = BigDecimal.ZERO;   // settled gross (payment lines)
        BigDecimal settledRefund = BigDecimal.ZERO;   // Σ |negative lines| (refund clawbacks)

        for (SettlementBatchEntity batch : batches) {
            settledNet = settledNet.add(nvl(batch.getNetSettlementAmount()));
            settledFee = settledFee.add(nvl(batch.getMerchantFeeTotal()));
            for (SettlementLineEntity line : lineRepository.findByBatchId(batch.getBatchId())) {
                BigDecimal amount = nvl(line.getAmount());
                if (amount.signum() >= 0) {
                    linesPositive = linesPositive.add(amount);
                } else {
                    settledRefund = settledRefund.add(amount.negate());
                }
            }
        }

        BigDecimal txnGross = unbatchedGross.add(linesPositive);

        // gross = net + fee + refund invariant, value-equal regardless of scale.
        boolean settlementBalanced =
                settledNet.add(settledFee).add(settledRefund).compareTo(linesPositive) == 0;

        BigDecimal ledgerFee = ledgerRevenuePort.revenueFor(businessDate);
        boolean ledgerAvailable = ledgerFee != null;
        BigDecimal ledgerDelta = ledgerAvailable ? settledFee.subtract(ledgerFee) : null;

        boolean tiedOut = settlementBalanced && (!ledgerAvailable || ledgerDelta.signum() == 0);

        return new TieOutReport(
                businessDate,
                txnGross,
                settledNet,
                settledFee,
                settledRefund,
                settlementBalanced,
                ledgerAvailable,
                ledgerFee,
                ledgerDelta,
                tiedOut);
    }

    private static BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
