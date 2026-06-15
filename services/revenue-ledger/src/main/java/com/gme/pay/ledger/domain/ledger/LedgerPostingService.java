package com.gme.pay.ledger.domain.ledger;

import com.gme.pay.ledger.domain.model.EntryType;
import com.gme.pay.ledger.domain.model.Journal;
import com.gme.pay.ledger.domain.model.LedgerEntry;
import com.gme.pay.ledger.fees.FeeShareResult;
import com.gme.pay.ledger.fees.SchemeFeeSplitCalculator;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

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
     */
    public Journal postRoundingResidual(String reference, BigDecimal residual, String currency) {
        Objects.requireNonNull(reference, "reference required");
        Objects.requireNonNull(residual, "residual required");
        Objects.requireNonNull(currency, "currency required");
        if (residual.signum() == 0) {
            return null;
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
     * Post the FX-margin and service-charge revenue entries for a committed transaction.
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
