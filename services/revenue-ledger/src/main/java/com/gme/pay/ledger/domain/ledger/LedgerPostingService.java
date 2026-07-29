package com.gme.pay.ledger.domain.ledger;

import com.gme.pay.ledger.domain.model.EntryType;
import com.gme.pay.ledger.domain.model.Journal;
import com.gme.pay.ledger.domain.model.LedgerEntry;
import com.gme.pay.ledger.fees.FeeShareResult;
import com.gme.pay.ledger.fees.SchemeFeeSplitCalculator;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Posts double-entry journal entries to the revenue ledger.
 *
 * <h2>Account codes used</h2>
 * <ul>
 *   <li>{@code REVENUE_FX_MARGIN} — FX margin income (asset/income account, credited)</li>
 *   <li>{@code REVENUE_SERVICE_CHARGE} — service-charge income (credited)</li>
 *   <li>{@code REVENUE_GME_FEE_SHARE} — GME 70% scheme fee share (credited)</li>
 *   <li>{@code RECEIVABLE_PARTNER} — partner receivable (debited)</li>
 *   <li>{@code PAYABLE_SCHEME} — amount payable to scheme/ZeroPay (debited in fee-share posting)</li>
 * </ul>
 *
 * <h2>Which methods the money path actually calls (T2-4)</h2>
 * The live capture path calls the two {@code *Committed*} methods below —
 * {@link #postCapturedRevenueJournal} (from {@code RevenueCaptureService}) and
 * {@link #postCommissionSplitJournal} (from {@code CommissionSplitRecordService}) — both idempotent
 * on the transaction reference and both invoked inside the SAME transaction as the revenue /
 * commission-split record, so a record and its journal can never diverge. The older
 * {@link #postRevenueCapture} / {@link #postFeeShareSplit} pair is retained unchanged for the
 * existing tests and callers that pass raw rates; new production wiring must use the idempotent pair.
 */
@Service
public class LedgerPostingService {

    private static final String ACC_FX_MARGIN        = "REVENUE_FX_MARGIN";
    private static final String ACC_SERVICE_CHARGE   = "REVENUE_SERVICE_CHARGE";
    private static final String ACC_GME_FEE_SHARE    = "REVENUE_GME_FEE_SHARE";
    private static final String ACC_RECEIVABLE       = "RECEIVABLE_PARTNER";
    private static final String ACC_PAYABLE_SCHEME   = "PAYABLE_SCHEME";
    private static final String ACC_ROUNDING         = "REVENUE_ROUNDING"; // rounding gain/loss vs partner booking
    private static final String ACC_REVERSAL         = "REVENUE_REVERSAL"; // contra-revenue for cancel/refund reversals

    private static final String USD = "USD";
    private static final String KRW = "KRW";

    /**
     * Income accounts an ORIGINAL revenue capture CREDITs. Presence of a CREDIT to any of them for a
     * reference means the capture journal was already posted (see {@link #alreadyCreditedFor}).
     */
    private static final Set<String> CAPTURE_INCOME_ACCOUNTS = Set.of(ACC_FX_MARGIN, ACC_SERVICE_CHARGE);

    private final JournalStore journalStore;
    private final SchemeFeeSplitCalculator calculator;

    public LedgerPostingService(JournalStore journalStore, SchemeFeeSplitCalculator calculator) {
        this.journalStore = Objects.requireNonNull(journalStore);
        this.calculator = Objects.requireNonNull(calculator);
    }

    /**
     * Post the rounding residual that arises when a partner books its settlement liability under a
     * different rounding rule than GMEPay+'s precise computed amount. {@code residual = precise - booked}.
     * Positive residual = rounding GAIN (we booked less liability than precise); negative = rounding LOSS.
     * Returns {@code null} when the residual is zero (nothing to post).
     *
     * <p><b>Idempotent on {@code reference}.</b> The {@code reference} is the audit handle of whatever
     * produced the residual — a settlement batch id (settlement-reconciliation) or a TXN ref
     * (payment-executor). A repeat call with a reference for which a rounding journal already exists is a
     * no-op: it does NOT create a second journal line and returns the previously-posted journal
     * unchanged, so retries leave the running {@code total_rounding_usd} aggregate counting the residual
     * exactly once. A DB partial-unique index on {@code ledger_entries(reference)} where
     * {@code account = 'REVENUE_ROUNDING'} (Flyway V006) backstops this against concurrent retries.
     * Only rounding journals touch {@code REVENUE_ROUNDING}, so this guard does not interfere with the
     * revenue-capture / fee-share / reversal journals that may carry the same {@code reference} on other
     * accounts.
     */
    public Journal postRoundingResidual(String reference, BigDecimal residual, String currency) {
        Objects.requireNonNull(reference, "reference required");
        Objects.requireNonNull(residual, "residual required");
        Objects.requireNonNull(currency, "currency required");
        if (residual.signum() == 0) {
            return null;
        }
        // Idempotency guard: if a rounding residual was already posted for this reference, return it
        // unchanged so a settlement-reconciliation / payment-executor retry never double-books.
        var existing = journalStore.findRoundingResidualByReference(reference);
        if (existing.isPresent()) {
            return existing.get();
        }
        BigDecimal amount = residual.abs();
        List<LedgerEntry> entries;
        if (residual.signum() > 0) {
            // booked liability < precise -> GME keeps the difference: rounding GAIN
            entries = List.of(
                new LedgerEntry(ACC_RECEIVABLE, amount, currency, EntryType.DEBIT,  reference),
                new LedgerEntry(ACC_ROUNDING,   amount, currency, EntryType.CREDIT, reference));
        } else {
            // booked liability > precise -> GME absorbs the difference: rounding LOSS
            entries = List.of(
                new LedgerEntry(ACC_ROUNDING,   amount, currency, EntryType.DEBIT,  reference),
                new LedgerEntry(ACC_RECEIVABLE, amount, currency, EntryType.CREDIT, reference));
        }
        return journalStore.save(Journal.post(entries));
    }

    /**
     * Post a structured reversal journal when a payment is cancelled/refunded. Contra-books the
     * revenue/receivable for {@code reference} as a balanced
     * {@code DEBIT REVENUE_REVERSAL / CREDIT RECEIVABLE_PARTNER} for {@code reversalAmount} — so the
     * cancellation is recorded in the ledger rather than absorbed as a zero residual. Returns
     * {@code null} when the amount is zero (nothing to post).
     *
     * @param reference      the cancelled transaction reference
     * @param reversalAmount the amount being reversed (absolute value used)
     * @param currency       ISO currency code of {@code reversalAmount}
     */
    public Journal postReversalJournal(String reference, BigDecimal reversalAmount, String currency) {
        Objects.requireNonNull(reference, "reference required");
        Objects.requireNonNull(reversalAmount, "reversalAmount required");
        Objects.requireNonNull(currency, "currency required");
        if (reversalAmount.signum() == 0) {
            return null;
        }
        BigDecimal amount = reversalAmount.abs();
        List<LedgerEntry> entries = List.of(
                new LedgerEntry(ACC_REVERSAL,   amount, currency, EntryType.DEBIT,  reference),
                new LedgerEntry(ACC_RECEIVABLE, amount, currency, EntryType.CREDIT, reference));
        return journalStore.save(Journal.post(entries));
    }

    /**
     * Post the balanced double-entry journal for one committed transaction's <b>captured revenue</b>
     * (FX margin + service charge) — the T2-4 fix that puts the main P&amp;L into the journal instead of
     * leaving it in the single-entry {@code revenue_records} store.
     *
     * <p>Journal layout (balanced per currency; only non-zero components produce lines):
     * <pre>
     *   DEBIT  RECEIVABLE_PARTNER      fxMarginUsd    USD
     *   CREDIT REVENUE_FX_MARGIN       fxMarginUsd    USD
     *   DEBIT  RECEIVABLE_PARTNER      serviceCharge  &lt;ccy&gt;
     *   CREDIT REVENUE_SERVICE_CHARGE  serviceCharge  &lt;ccy&gt;
     * </pre>
     * Every account here already existed in the module's chart of accounts and both DR/CR sides are
     * exactly the ones {@link #postRevenueCapture} has always used — no account code and no accounting
     * policy is introduced by this method.
     *
     * <p><b>Idempotent on {@code reference}.</b> A CREDIT to {@code REVENUE_FX_MARGIN} or
     * {@code REVENUE_SERVICE_CHARGE} is only ever produced by an original capture (a reversal
     * mirrors the sides, so it DEBITs those accounts — see {@link RevenueReversalService}). If such a
     * credit already exists for the reference, this is a no-op returning {@link Optional#empty()}, so a
     * replayed capture / redelivered {@code payment.approved} can never double-book. The DB backstop is
     * the {@code UNIQUE(txn_ref)} constraint on {@code revenue_records}: the caller writes the record and
     * this journal in one transaction, so a concurrent duplicate loses the record insert and its journal
     * rolls back with it.
     *
     * <p>Unlike {@link #postRevenueCapture} this does <b>not</b> post a nominal zero-amount journal when
     * there is no revenue (CFO#14): a genuinely zero-revenue transaction yields
     * {@link Optional#empty()} and is reported as {@code zeroRevenue} by the reconciliation self-check
     * rather than as ledger noise.
     *
     * @param reference        transaction reference (e.g. {@code "TXN-00001"})
     * @param fxMarginUsd      FX-margin income in USD (must be &gt;= 0)
     * @param serviceCharge    service-charge income amount (must be &gt;= 0)
     * @param serviceChargeCcy ISO-4217 currency of {@code serviceCharge}
     * @return the newly posted journal, or {@link Optional#empty()} when already journalled / zero revenue
     */
    public Optional<Journal> postCapturedRevenueJournal(String reference,
                                                       BigDecimal fxMarginUsd,
                                                       BigDecimal serviceCharge,
                                                       String serviceChargeCcy) {
        Objects.requireNonNull(reference, "reference required");
        Objects.requireNonNull(fxMarginUsd, "fxMarginUsd required");
        Objects.requireNonNull(serviceCharge, "serviceCharge required");
        Objects.requireNonNull(serviceChargeCcy, "serviceChargeCcy required");
        if (fxMarginUsd.signum() < 0) {
            throw new IllegalArgumentException("fxMarginUsd must be >= 0, got: " + fxMarginUsd);
        }
        if (serviceCharge.signum() < 0) {
            throw new IllegalArgumentException("serviceCharge must be >= 0, got: " + serviceCharge);
        }
        if (fxMarginUsd.signum() == 0 && serviceCharge.signum() == 0) {
            return Optional.empty();
        }
        if (alreadyCreditedFor(reference, CAPTURE_INCOME_ACCOUNTS)) {
            return Optional.empty();
        }

        List<LedgerEntry> entries = new ArrayList<>(4);
        if (fxMarginUsd.signum() > 0) {
            entries.add(new LedgerEntry(ACC_RECEIVABLE, fxMarginUsd, USD, EntryType.DEBIT, reference));
            entries.add(new LedgerEntry(ACC_FX_MARGIN, fxMarginUsd, USD, EntryType.CREDIT, reference));
        }
        if (serviceCharge.signum() > 0) {
            entries.add(new LedgerEntry(ACC_RECEIVABLE, serviceCharge, serviceChargeCcy, EntryType.DEBIT, reference));
            entries.add(new LedgerEntry(ACC_SERVICE_CHARGE, serviceCharge, serviceChargeCcy, EntryType.CREDIT, reference));
        }
        return Optional.of(journalStore.save(Journal.post(entries)));
    }

    /**
     * Post the balanced double-entry journal for one committed transaction's <b>commission split</b>,
     * from the already-computed KRW amounts on the {@code commission_splits} record (so the journal can
     * never drift from the record by re-deriving the split).
     *
     * <p>Journal layout (balanced in KRW — this is the SCHEME-side leg of the two-sided split, and is
     * the exact DR/CR shape {@link #postFeeShareSplit} has always used):
     * <pre>
     *   DEBIT  RECEIVABLE_PARTNER     netMerchantFeeKrw   KRW
     *   CREDIT REVENUE_GME_FEE_SHARE  gmeGrossShareKrw    KRW
     *   CREDIT PAYABLE_SCHEME         schemeShareKrw      KRW
     * </pre>
     *
     * <p><b>Deliberately NOT journalled here: the partner-side leg.</b> The second split
     * ({@code partnerShareKrw} — the wallet partner's carve out of GME's gross commission, plus the
     * {@code gmeNetShareKrw} remainder) has <b>no account code in this module</b>. Booking it would
     * require inventing a partner-commission payable/expense account, which is a finance-owner
     * decision, so it is left unposted and surfaced explicitly by the reconciliation self-check
     * ({@code GET /v1/revenue/journal-reconciliation} → {@code unmappedComponents}) instead of being
     * silently dropped. Consequence while the decision is outstanding: {@code REVENUE_GME_FEE_SHARE}
     * carries GME's GROSS commission, i.e. it overstates GME's retained commission by exactly
     * {@code partnerShareKrw}.
     *
     * <p><b>Idempotent on {@code reference}</b> via a CREDIT to {@code REVENUE_GME_FEE_SHARE} (only an
     * original split posts one; a reversal mirrors it as a DEBIT). Backstopped by
     * {@code UNIQUE(txn_ref)} on {@code commission_splits} because the caller records and journals in
     * one transaction.
     *
     * @param reference         transaction reference
     * @param netMerchantFeeKrw net merchant fee for the transaction (whole KRW, &gt;= 0)
     * @param gmeGrossShareKrw  GME's cut of the net fee BEFORE the partner carve (whole KRW, &gt;= 0)
     * @param schemeShareKrw    the scheme operator's cut of the net fee (whole KRW, &gt;= 0)
     * @return the newly posted journal, or {@link Optional#empty()} when already journalled / zero fee
     * @throws IllegalArgumentException if the amounts are negative or do not satisfy
     *                                  {@code gmeGrossShareKrw + schemeShareKrw == netMerchantFeeKrw}
     *                                  (posting an unbalanced split is refused, never silently fixed)
     */
    public Optional<Journal> postCommissionSplitJournal(String reference,
                                                       long netMerchantFeeKrw,
                                                       long gmeGrossShareKrw,
                                                       long schemeShareKrw) {
        Objects.requireNonNull(reference, "reference required");
        if (netMerchantFeeKrw < 0 || gmeGrossShareKrw < 0 || schemeShareKrw < 0) {
            throw new IllegalArgumentException("commission-split amounts must be >= 0, got net="
                    + netMerchantFeeKrw + " gmeGross=" + gmeGrossShareKrw + " scheme=" + schemeShareKrw);
        }
        if (gmeGrossShareKrw + schemeShareKrw != netMerchantFeeKrw) {
            throw new IllegalArgumentException("commission split does not conserve KRW: gmeGross("
                    + gmeGrossShareKrw + ") + scheme(" + schemeShareKrw + ") != net(" + netMerchantFeeKrw + ")");
        }
        if (netMerchantFeeKrw == 0) {
            return Optional.empty();
        }
        if (alreadyCreditedFor(reference, Set.of(ACC_GME_FEE_SHARE))) {
            return Optional.empty();
        }

        List<LedgerEntry> entries = new ArrayList<>(3);
        entries.add(new LedgerEntry(ACC_RECEIVABLE, BigDecimal.valueOf(netMerchantFeeKrw), KRW,
                EntryType.DEBIT, reference));
        if (gmeGrossShareKrw > 0) {
            entries.add(new LedgerEntry(ACC_GME_FEE_SHARE, BigDecimal.valueOf(gmeGrossShareKrw), KRW,
                    EntryType.CREDIT, reference));
        }
        if (schemeShareKrw > 0) {
            entries.add(new LedgerEntry(ACC_PAYABLE_SCHEME, BigDecimal.valueOf(schemeShareKrw), KRW,
                    EntryType.CREDIT, reference));
        }
        return Optional.of(journalStore.save(Journal.post(entries)));
    }

    /**
     * True when {@code reference} already carries a CREDIT line to one of {@code incomeAccounts} — the
     * idempotency probe shared by the two capture-side posts. Mirrors
     * {@link RevenueReversalService}'s "a DEBIT to a REVENUE_* account marks a reversal" rule from the
     * other side: only an ORIGINAL posting ever CREDITs an income account.
     */
    private boolean alreadyCreditedFor(String reference, Set<String> incomeAccounts) {
        return journalStore.findByReference(reference).stream()
                .flatMap(j -> j.entries().stream())
                .anyMatch(e -> e.type() == EntryType.CREDIT && incomeAccounts.contains(e.account()));
    }

    /**
     * Post the FX-margin and service-charge revenue entries for a committed transaction.
     *
     * <p><b>Superseded for production wiring</b> by {@link #postCapturedRevenueJournal}, which is
     * idempotent on the reference and skips the zero-amount nominal journal. Kept unchanged.
     *
     * <p>Journal layout (balanced):
     * <pre>
     *   DEBIT  RECEIVABLE_PARTNER    fxMarginUsd   USD  (GME earned this)
     *   CREDIT REVENUE_FX_MARGIN     fxMarginUsd   USD
     *
     *   DEBIT  RECEIVABLE_PARTNER    serviceCharge  &lt;ccy&gt;
     *   CREDIT REVENUE_SERVICE_CHARGE serviceCharge &lt;ccy&gt;
     * </pre>
     *
     * @param reference      transaction reference (e.g. "TXN-00001")
     * @param fxMarginUsd    FX margin income in USD (must be &gt;= 0)
     * @param serviceCharge  service-charge income amount (must be &gt;= 0)
     * @param serviceChargeCcy ISO currency code for the service charge
     * @return the stored, validated {@link Journal}
     */
    public Journal postRevenueCapture(String reference,
                                      BigDecimal fxMarginUsd,
                                      BigDecimal serviceCharge,
                                      String serviceChargeCcy) {
        Objects.requireNonNull(reference, "reference required");
        Objects.requireNonNull(fxMarginUsd, "fxMarginUsd required");
        Objects.requireNonNull(serviceCharge, "serviceCharge required");
        Objects.requireNonNull(serviceChargeCcy, "serviceChargeCcy required");

        var entries = new java.util.ArrayList<LedgerEntry>();

        // FX margin lines (only if non-zero to keep journal clean)
        if (fxMarginUsd.compareTo(BigDecimal.ZERO) > 0) {
            entries.add(new LedgerEntry(ACC_RECEIVABLE,    fxMarginUsd, "USD", EntryType.DEBIT,  reference));
            entries.add(new LedgerEntry(ACC_FX_MARGIN,     fxMarginUsd, "USD", EntryType.CREDIT, reference));
        }

        // Service-charge lines (always post, even if zero — zero-amount entries are valid no-ops)
        if (serviceCharge.compareTo(BigDecimal.ZERO) > 0) {
            entries.add(new LedgerEntry(ACC_RECEIVABLE,      serviceCharge, serviceChargeCcy, EntryType.DEBIT,  reference));
            entries.add(new LedgerEntry(ACC_SERVICE_CHARGE,  serviceCharge, serviceChargeCcy, EntryType.CREDIT, reference));
        }

        if (entries.isEmpty()) {
            // Zero revenue — post a nominal balanced journal so there is always a trace
            entries.add(new LedgerEntry(ACC_RECEIVABLE,    BigDecimal.ZERO.setScale(4), "USD", EntryType.DEBIT,  reference));
            entries.add(new LedgerEntry(ACC_FX_MARGIN,     BigDecimal.ZERO.setScale(4), "USD", EntryType.CREDIT, reference));
        }

        Journal journal = Journal.post(entries);
        return journalStore.save(journal);
    }

    /**
     * Post the scheme fee-share split entries for a committed transaction.
     *
     * <p>Journal layout (balanced, amounts in KRW):
     * <pre>
     *   DEBIT  PAYABLE_SCHEME          netMerchantFeeKrw  KRW  (we owe scheme the net fee)
     *   CREDIT REVENUE_GME_FEE_SHARE   gmeFeeShareKrw     KRW  (our 70% cut)
     *   CREDIT PAYABLE_SCHEME          zeropayFeeShareKrw KRW  (ZeroPay's 30% — remains payable)
     * </pre>
     * Wait — that does not balance PAYABLE_SCHEME. Correct layout:
     * <pre>
     *   DEBIT  RECEIVABLE_PARTNER      netMerchantFeeKrw  KRW
     *   CREDIT REVENUE_GME_FEE_SHARE   gmeFeeShareKrw     KRW
     *   CREDIT PAYABLE_SCHEME          zeropayFeeShareKrw KRW
     * </pre>
     *
     * @param reference          transaction reference
     * @param payoutAmountKrw    KRW payout for the transaction
     * @param merchantFeeRate    gross merchant fee rate (e.g. 0.0080)
     * @param vanFeeRate         VAN intermediary fee rate (e.g. 0.0008)
     * @param gmeFeeSharePct     GME's share percentage (e.g. 0.70)
     * @return the stored, validated {@link Journal}
     */
    public Journal postFeeShareSplit(String reference,
                                     long payoutAmountKrw,
                                     BigDecimal merchantFeeRate,
                                     BigDecimal vanFeeRate,
                                     BigDecimal gmeFeeSharePct) {
        Objects.requireNonNull(reference, "reference required");

        FeeShareResult split = calculator.calculate(payoutAmountKrw, merchantFeeRate, vanFeeRate, gmeFeeSharePct);

        long net     = split.netMerchantFeeKrw();
        long gme     = split.gmeFeeShareKrw();
        long zeropay = split.zeropayFeeShareKrw();

        List<LedgerEntry> entries;
        if (net == 0) {
            // Zero net fee — post nominal balanced journal
            entries = List.of(
                new LedgerEntry(ACC_RECEIVABLE,    BigDecimal.ZERO, "KRW", EntryType.DEBIT,  reference),
                new LedgerEntry(ACC_GME_FEE_SHARE, BigDecimal.ZERO, "KRW", EntryType.CREDIT, reference)
            );
        } else {
            entries = List.of(
                new LedgerEntry(ACC_RECEIVABLE,    BigDecimal.valueOf(net),     "KRW", EntryType.DEBIT,  reference),
                new LedgerEntry(ACC_GME_FEE_SHARE, BigDecimal.valueOf(gme),     "KRW", EntryType.CREDIT, reference),
                new LedgerEntry(ACC_PAYABLE_SCHEME, BigDecimal.valueOf(zeropay), "KRW", EntryType.CREDIT, reference)
            );
        }

        Journal journal = Journal.post(entries);
        return journalStore.save(journal);
    }
}
