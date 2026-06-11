package com.gme.pay.registry.bank;

/**
 * What a partner bank account is used for — mirrors the V012
 * {@code ck_partner_bank_account_purpose} CHECK roster. Settlement (Slice 6)
 * pays out to PAYOUT rows; prefunding reads FLOAT_TOPUP; REFUND covers the
 * return leg of reversed transactions.
 */
public enum BankAccountPurpose {

    /** Settlement payouts land here (the V012 column default). */
    PAYOUT,

    /** The partner tops up their prefunded float from / through this account. */
    FLOAT_TOPUP,

    /** Refund/return-leg account when it differs from the payout account. */
    REFUND
}
