package com.gme.pay.registry.bank;

/**
 * SWIFT charge-bearer code (MT103 field 71A) for cross-border payouts —
 * mirrors the V012 {@code ck_partner_bank_account_charge_bearer} CHECK roster.
 * {@code null} on the row means a domestic rail with no SWIFT leg.
 */
public enum SwiftChargeBearer {

    /** All transaction charges are borne by the ordering customer (us). */
    OUR,

    /** All transaction charges are borne by the beneficiary (the partner). */
    BEN,

    /** Charges are shared: ours on our side, theirs on their side. */
    SHA
}
