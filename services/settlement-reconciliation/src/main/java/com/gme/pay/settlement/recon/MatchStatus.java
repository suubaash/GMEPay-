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
    MISSING_INTERNAL
}
