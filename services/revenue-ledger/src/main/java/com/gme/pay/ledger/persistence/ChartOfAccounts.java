package com.gme.pay.ledger.persistence;

/**
 * Chart-of-accounts row constants for the revenue ledger.
 *
 * <p>This is the canonical list of account codes posted to by
 * {@link com.gme.pay.ledger.domain.ledger.LedgerPostingService}. Keeping the codes
 * in one place lets reporting/admin tooling reference them without literal strings.
 *
 * <p>{@code REVENUE_ROUNDING} is mandated by
 * {@code docs/MONEY_CONVENTION.md} — every rounding residual (the difference
 * between GMEPay+'s precise computed settlement amount and the partner's
 * rounded booked amount) is posted as a balanced GAIN or LOSS journal against
 * this account so the difference is always visible in the ledger, never silently
 * absorbed.
 */
public final class ChartOfAccounts {

    /** FX margin income (credited on revenue capture). */
    public static final String REVENUE_FX_MARGIN = "REVENUE_FX_MARGIN";

    /** Service-charge income (credited on revenue capture). */
    public static final String REVENUE_SERVICE_CHARGE = "REVENUE_SERVICE_CHARGE";

    /** GME's 70% share of the scheme fee (credited on fee-share posting). */
    public static final String REVENUE_GME_FEE_SHARE = "REVENUE_GME_FEE_SHARE";

    /** Partner receivable (debited on revenue capture and fee-share split). */
    public static final String RECEIVABLE_PARTNER = "RECEIVABLE_PARTNER";

    /** Amount payable to scheme / ZeroPay (credited for the scheme's 30% share). */
    public static final String PAYABLE_SCHEME = "PAYABLE_SCHEME";

    /**
     * <b>T2-10</b> — commission GME pays the wallet partner out of its OWN earned commission (debited on
     * the partner-side leg of the two-sided split). An expense, not a reduction of revenue: GME bills the
     * merchant for the whole merchant fee (SETTLEMENT_FLOW_SPEC D11) and the carve is a fraction of GME's
     * resulting cut ({@code partner = gme × partner_share_pct}, V031/V032), so the money was GME's income
     * before any of it was paid away. Per the owner's ruling — <i>"if it's our income then it should be
     * booked as revenue; if this is payout cost of partner then it is payable expense."</i>
     */
    public static final String EXPENSE_PARTNER_COMMISSION = "EXPENSE_PARTNER_COMMISSION";

    /**
     * <b>T2-10</b> — liability to the wallet partner for that commission carve (credited). The exact
     * mirror of {@link #PAYABLE_SCHEME} for the other counterparty; {@code PAYABLE_SCHEME} itself could
     * not be reused because it is the scheme operator's liability.
     */
    public static final String PAYABLE_PARTNER = "PAYABLE_PARTNER";

    /**
     * Rounding gain/loss account per {@code docs/MONEY_CONVENTION.md}.
     * Credited when {@code residual > 0} (rounding GAIN — partner booked less than precise),
     * debited when {@code residual < 0} (rounding LOSS — partner booked more than precise).
     */
    public static final String REVENUE_ROUNDING = "REVENUE_ROUNDING";

    private ChartOfAccounts() {
        // constants only
    }
}
