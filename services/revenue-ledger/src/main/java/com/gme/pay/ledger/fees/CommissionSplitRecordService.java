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
 * <p><b>Double-entry (T2-4 + T2-10).</b> In the SAME transaction as the record, BOTH legs of the split
 * are journalled from the amounts stored on the record, so journal and record cannot drift:
 * <ul>
 *   <li>SCHEME side — {@link LedgerPostingService#postCommissionSplitJournal}:
 *       {@code DEBIT RECEIVABLE_PARTNER net / CREDIT REVENUE_GME_FEE_SHARE gmeGross /
 *       CREDIT PAYABLE_SCHEME schemeShare}.</li>
 *   <li>PARTNER side (T2-10) — {@link LedgerPostingService#postPartnerCommissionCarveJournal}:
 *       {@code DEBIT EXPENSE_PARTNER_COMMISSION partnerShare / CREDIT PAYABLE_PARTNER partnerShare}.
 *       GME collects the whole merchant fee and the carve is a fraction of GME's own resulting
 *       commission, so per the owner's rule it is a cost paid to the partner, not commission GME never
 *       earned. See that method for the money-flow evidence.</li>
 * </ul>
 * Two separate journals, each balanced on its own lines and each independently idempotent, so a split
 * journalled before T2-10 has its carve back-filled on replay.
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
     * Journal both legs of {@code record}'s split. Each leg is independently idempotent on {@code txnRef};
     * a zero net merchant fee posts no scheme leg and a zero carve posts no partner leg.
     */
    private void journal(CommissionSplitRecordEntity record) {
        ledgerPostingService.postCommissionSplitJournal(
                        record.getTxnRef(),
                        record.getNetMerchantFeeKrw(),
                        record.getGmeGrossShareKrw(),
                        record.getSchemeShareKrw())
                .ifPresent(j -> log.info(
                        "commission split journalled (scheme leg): txnRef={} journalId={} net={} "
                                + "gmeGross={} scheme={}",
                        record.getTxnRef(), j.journalId(), record.getNetMerchantFeeKrw(),
                        record.getGmeGrossShareKrw(), record.getSchemeShareKrw()));
        // T2-10: the partner carve is a cost GME pays out of commission it earned — posted as its own
        // balanced journal so it back-fills for splits journalled before that decision was taken.
        ledgerPostingService.postPartnerCommissionCarveJournal(
                        record.getTxnRef(), record.getPartnerShareKrw())
                .ifPresent(j -> log.info(
                        "commission split journalled (partner leg): txnRef={} journalId={} "
                                + "partnerShare={} gmeNetRetained={}",
                        record.getTxnRef(), j.journalId(), record.getPartnerShareKrw(),
                        record.getGmeNetShareKrw()));
    }
}
