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
     * Rounding gain/loss account per {@code docs/MONEY_CONVENTION.md}.
     * Credited when {@code residual > 0} (rounding GAIN — partner booked less than precise),
     * debited when {@code residual < 0} (rounding LOSS — partner booked more than precise).
     */
    public static final String REVENUE_ROUNDING = "REVENUE_ROUNDING";

    private ChartOfAccounts() {
        // constants only
    }
}
