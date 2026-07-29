package com.gme.pay.ledger.fees;

import com.gme.pay.ledger.domain.ledger.LedgerPostingService;
import com.gme.pay.ledger.persistence.CommissionSplitRecordEntity;
import com.gme.pay.ledger.persistence.CommissionSplitRecordRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Threads the {@link CommissionSplitCalculator} into live revenue posting (Step 7 / task #102):
 * computes the two-sided commission split for one committed transaction from the resolved
 * configurable shares (V031) and persists it to {@code commission_splits} (V005).
 *
 * <p>Idempotent by {@code txnRef} — a repeat returns the existing row without recomputing, so the
 * payment-executor's non-blocking post-at-confirm can safely retry.
 *
 * <p><b>Double-entry (T2-4).</b> In the SAME transaction as the record, the SCHEME-side leg of the
 * split is journalled via {@link LedgerPostingService#postCommissionSplitJournal}
 * ({@code DEBIT RECEIVABLE_PARTNER net / CREDIT REVENUE_GME_FEE_SHARE gmeGross / CREDIT PAYABLE_SCHEME
 * schemeShare}), from the amounts stored on the record so journal and record cannot drift. The
 * PARTNER-side leg ({@code partnerShareKrw}) is <b>not</b> journalled — this module has no account code
 * for the partner's commission carve and inventing one is a finance-owner decision; it is reported by
 * {@code GET /v1/revenue/journal-reconciliation} as an unmapped component instead of being dropped
 * silently.
 */
@Service
public class CommissionSplitRecordService {

    private static final Logger log = LoggerFactory.getLogger(CommissionSplitRecordService.class);

    private final CommissionSplitRecordRepository repository;
    private final CommissionSplitCalculator calculator;
    private final LedgerPostingService ledgerPostingService;

    public CommissionSplitRecordService(CommissionSplitRecordRepository repository,
                                        CommissionSplitCalculator calculator,
                                        LedgerPostingService ledgerPostingService) {
        this.repository = repository;
        this.calculator = calculator;
        this.ledgerPostingService = ledgerPostingService;
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
            // Idempotent journal post so a split recorded before T2-4 is back-filled on any replay.
            journal(existing.get());
            return new Result(existing.get(), false);
        }
        CommissionSplit split = calculator.calculate(
                payoutAmountKrw, merchantFeeRate, vanFeeRate, gmeSharePct, partnerSharePct);
        CommissionSplitRecordEntity saved = repository.save(CommissionSplitRecordEntity.of(
                txnRef, partnerId, schemeId, revenueDate, payoutAmountKrw,
                merchantFeeRate, vanFeeRate, gmeSharePct, partnerSharePct, split, Instant.now()));
        journal(saved);
        return new Result(saved, true);
    }

    /**
     * Journal the scheme-side leg of {@code record}'s split. Idempotent on {@code txnRef}; a zero net
     * merchant fee posts nothing.
     */
    private void journal(CommissionSplitRecordEntity record) {
        ledgerPostingService.postCommissionSplitJournal(
                        record.getTxnRef(),
                        record.getNetMerchantFeeKrw(),
                        record.getGmeGrossShareKrw(),
                        record.getSchemeShareKrw())
                .ifPresent(j -> log.info(
                        "commission split journalled (scheme leg): txnRef={} journalId={} net={} "
                                + "gmeGross={} scheme={} partnerShareKrw={} NOT journalled (no account code)",
                        record.getTxnRef(), j.journalId(), record.getNetMerchantFeeKrw(),
                        record.getGmeGrossShareKrw(), record.getSchemeShareKrw(),
                        record.getPartnerShareKrw()));
    }
}
