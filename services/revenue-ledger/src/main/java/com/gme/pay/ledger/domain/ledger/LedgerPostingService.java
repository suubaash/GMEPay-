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
 *   <li>{@code EXPENSE_PARTNER_COMMISSION} — commission paid to the wallet partner out of GME's own
 *       earned commission (debited; <b>T2-10</b>, the two account codes added by that decision)</li>
 *   <li>{@code PAYABLE_PARTNER} — amount payable to the wallet partner for that commission (credited)</li>
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

    /**
     * T2-10 — the two account codes the owner's ruling required. GME collects the WHOLE merchant fee
     * (it bills the merchant directly, SETTLEMENT_FLOW_SPEC D11) and the partner's carve is a fraction
     * of GME's <em>own</em> earned commission ({@code partner = gme × partner_share_pct}, V032 header),
     * so the carve is a <b>cost GME pays the partner</b>, not commission GME never earned:
     * "if it's our income then it should be booked as revenue; if this is payout cost of partner then it
     * is payable expense."
     */
    private static final String ACC_PARTNER_COMMISSION_EXPENSE = "EXPENSE_PARTNER_COMMISSION";

    /** Liability to the wallet partner for its commission carve — the mirror of {@code PAYABLE_SCHEME}. */
    private static final String ACC_PAYABLE_PARTNER  = "PAYABLE_PARTNER";

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
     * <p><b>The partner-side leg is NOT posted here</b> — it is a separate balanced journal, posted by
     * {@link #postPartnerCommissionCarveJournal} in the same transaction (T2-10). Keeping it separate is
     * deliberate: it back-fills independently, so a split journalled before T2-10 (whose
     * {@code REVENUE_GME_FEE_SHARE} credit already satisfies this method's idempotency probe) still gets
     * its carve booked on any replay. {@code REVENUE_GME_FEE_SHARE} legitimately carries GME's GROSS
     * commission — the money GME earned — and the carve is booked as the cost of paying part of it away,
     * NOT as a reduction of it.
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
     * Post the balanced double-entry journal for the <b>partner-side leg</b> of the two-sided commission
     * split — the wallet partner's carve out of GME's own commission (T2-10).
     *
     * <h2>Why THIS treatment (the owner's rule applied to the established money flow)</h2>
     * The owner's ruling is a principle, not a preference: <i>"if it's our income then it should be booked
     * as revenue; if this is payout cost of partner then it is payable expense."</i> The flow says the
     * carve is a cost:
     * <ul>
     *   <li><b>GME collects the whole merchant fee.</b> GME bills the merchant directly and then shares a
     *       cut with the scheme (SETTLEMENT_FLOW_SPEC D11). No one else collects any part of it, and the
     *       partner never bills the merchant.</li>
     *   <li><b>The carve is computed off GME's own cut</b> — {@code partner = gme × partner_share_pct}
     *       (V032 header; {@code CommissionSplitCalculator} split 2). The amount only exists once GME's
     *       entitlement has been established, and the unconfigured default is "GME keeps 100% of its cut"
     *       ({@code EffectiveCommissionView}). It is a distribution OUT of GME's income.</li>
     *   <li><b>The receivable is not net of it.</b> The {@code net} debited to
     *       {@code RECEIVABLE_PARTNER} by {@link #postCommissionSplitJournal} is
     *       {@code netMerchantFeeKrw} = gross − VAN — net of the <em>VAN intermediary fee only</em>. GME's
     *       booked claim (1800 in the worked example) fully contains the carve (378 ⊂ gmeGross 1260), so
     *       GME does bill and collect it. The VAN fee is what a genuinely-netted deduction looks like in
     *       this model: subtracted before the split and never journalled at all.</li>
     * </ul>
     * Hence: <b>payable + expense</b>, not contra-revenue. Gross revenue is unchanged — it was always
     * right — and GME's retained commission now shows as {@code REVENUE_GME_FEE_SHARE − }this expense,
     * which equals {@code gmeNetShareKrw}.
     *
     * <p>Journal layout (balanced in KRW, two lines):
     * <pre>
     *   DEBIT  EXPENSE_PARTNER_COMMISSION  partnerShareKrw  KRW
     *   CREDIT PAYABLE_PARTNER             partnerShareKrw  KRW
     * </pre>
     * <b>Both codes are new</b> — see the field javadoc. {@code PAYABLE_SCHEME} could not be reused (a
     * different counterparty, and reusing it would misstate who is owed), and crediting the existing
     * {@code RECEIVABLE_PARTNER} was rejected because netting a liability into an asset contradicts the
     * word "payable" in the ruling and would understate both sides of the balance sheet.
     *
     * <p><b>Idempotent on {@code reference}</b> via a CREDIT to {@code PAYABLE_PARTNER}: only an original
     * carve ever credits that account (a reversal mirrors the sides and DEBITs it), the same rule the
     * other two capture-side posts use. Posted inside the caller's transaction alongside the
     * {@code commission_splits} row, so record and journal commit or roll back together. A zero carve
     * ({@code partner_share_pct = 0}, i.e. GME keeps everything) posts nothing rather than a nominal zero
     * journal, consistent with the other T2-4 posts.
     *
     * <p><b>Reversal.</b> {@link RevenueReversalService} mirrors every non-rounding line for the txnRef,
     * so a reversal automatically unwinds this journal too ({@code DEBIT PAYABLE_PARTNER / CREDIT
     * EXPENSE_PARTNER_COMMISSION}) and nets the carve to zero. Neither line is a {@code REVENUE_*} debit,
     * so it neither trips nor is tripped by that service's reversal probe. <b>T2-11 interaction:</b> that
     * mirror is not pro-rated, so a PARTIAL refund currently unwinds the whole carve exactly as it
     * unwinds the whole revenue — the carve inherits T2-11's open question rather than adding a new one,
     * and whatever pro-rating factor T2-11 chooses must be applied to this leg too (the carve is a fixed
     * fraction of {@code gmeGross}, so the same factor preserves the split invariant).
     *
     * @param reference       transaction reference
     * @param partnerShareKrw the partner's commission carve for the transaction (whole KRW, &gt;= 0)
     * @return the newly posted journal, or {@link Optional#empty()} when already journalled / zero carve
     * @throws IllegalArgumentException if {@code partnerShareKrw} is negative
     */
    public Optional<Journal> postPartnerCommissionCarveJournal(String reference, long partnerShareKrw) {
        Objects.requireNonNull(reference, "reference required");
        if (partnerShareKrw < 0) {
            throw new IllegalArgumentException(
                    "partnerShareKrw must be >= 0, got: " + partnerShareKrw);
        }
        if (partnerShareKrw == 0) {
            return Optional.empty();
        }
        if (alreadyCreditedFor(reference, Set.of(ACC_PAYABLE_PARTNER))) {
            return Optional.empty();
        }
        BigDecimal amount = BigDecimal.valueOf(partnerShareKrw);
        List<LedgerEntry> entries = List.of(
                new LedgerEntry(ACC_PARTNER_COMMISSION_EXPENSE, amount, KRW, EntryType.DEBIT, reference),
                new LedgerEntry(ACC_PAYABLE_PARTNER, amount, KRW, EntryType.CREDIT, reference));
        return Optional.of(journalStore.save(Journal.post(entries)));
    }

    /**
     * True when {@code reference} already carries a CREDIT line to one of {@code accounts} — the
     * idempotency probe shared by the three capture-side posts. Mirrors
     * {@link RevenueReversalService}'s "a DEBIT to a REVENUE_* account marks a reversal" rule from the
     * other side: only an ORIGINAL posting ever CREDITs one of these accounts (income for the two revenue
     * posts, {@code PAYABLE_PARTNER} for the commission carve — a reversal mirrors the sides and debits
     * it), so the credit's presence is proof the original was posted and survives a later reversal.
     */
    private boolean alreadyCreditedFor(String reference, Set<String> accounts) {
        return journalStore.findByReference(reference).stream()
                .flatMap(j -> j.entries().stream())
                .anyMatch(e -> e.type() == EntryType.CREDIT && accounts.contains(e.account()));
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
