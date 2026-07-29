package com.gme.pay.settlement.recon;

/** Line-match outcome for a single settlement record. */
public enum MatchStatus {
    /** Internal and scheme amounts match within tolerance. */
    MATCHED,
    /** Amounts differ beyond tolerance. */
    DISCREPANCY,
    /** Record present internally but absent from the scheme file. */
    MISSING_SCHEME,
    /** Record present in the scheme file but not found internally. */
    MISSING_INTERNAL,

    // --- cross-border three-way tie-out (GAP T2-2) ---

    /**
     * The transaction and the scheme confirmation both exist, but no prefunding movement was found
     * for it: the payment happened without the partner float being charged (or the deduct is lost).
     * Only raised by the three-way tie-out — the two-way ZeroPay file recon has no prefunding leg.
     */
    MISSING_PREFUNDING,

    /**
     * Every leg is present and the like-for-like amounts agree, but the two USD bases disagree
     * beyond tolerance: the USD deducted from the partner float (priced off live USD/KRW, or its
     * hardcoded fallback) is LESS than the USD owed to the scheme at the rate the scheme registered
     * with us. That gap is a real loss per payment and accrues silently — this classification, and
     * the signed daily total on {@code corridor_recon_summary}, is what makes it visible.
     */
    RATE_BASIS_VARIANCE
}
