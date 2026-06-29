package com.gme.pay.ledger.fees;

import com.gme.pay.ledger.persistence.CommissionSplitRecordEntity;
import com.gme.pay.ledger.persistence.CommissionSplitRecordRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Threads the {@link CommissionSplitCalculator} into live revenue posting (Step 7 / task #102):
 * computes the two-sided commission split for one committed transaction from the resolved
 * configurable shares (V031) and persists it to {@code commission_splits} (V005).
 *
 * <p>Idempotent by {@code txnRef} — a repeat returns the existing row without recomputing, so the
 * payment-executor's non-blocking post-at-confirm can safely retry.
 */
@Service
public class CommissionSplitRecordService {

    private final CommissionSplitRecordRepository repository;
    private final CommissionSplitCalculator calculator;

    public CommissionSplitRecordService(CommissionSplitRecordRepository repository,
                                        CommissionSplitCalculator calculator) {
        this.repository = repository;
        this.calculator = calculator;
    }

    /** Outcome of a record attempt: the stored row + whether this call inserted it (201 vs 200). */
    public record Result(CommissionSplitRecordEntity record, boolean created) {}

    /**
     * Compute + persist the split for {@code txnRef}, or return the existing row on replay.
     *
     * @throws IllegalArgumentException if the calculator's guards reject the inputs (bad rates/shares)
     */
    @Transactional
    public Result recordIfAbsent(String txnRef, long partnerId, long schemeId, LocalDate revenueDate,
                                 long payoutAmountKrw, BigDecimal merchantFeeRate, BigDecimal vanFeeRate,
                                 BigDecimal gmeSharePct, BigDecimal partnerSharePct) {
        Optional<CommissionSplitRecordEntity> existing = repository.findByTxnRef(txnRef);
        if (existing.isPresent()) {
            return new Result(existing.get(), false);
        }
        CommissionSplit split = calculator.calculate(
                payoutAmountKrw, merchantFeeRate, vanFeeRate, gmeSharePct, partnerSharePct);
        CommissionSplitRecordEntity saved = repository.save(CommissionSplitRecordEntity.of(
                txnRef, partnerId, schemeId, revenueDate, payoutAmountKrw,
                merchantFeeRate, vanFeeRate, gmeSharePct, partnerSharePct, split, Instant.now()));
        return new Result(saved, true);
    }
}
