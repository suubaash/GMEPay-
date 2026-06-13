package com.gme.pay.registry.bank;

/**
 * Verification verdict on a partner bank account — mirrors the V012
 * {@code ck_partner_bank_account_verification_status} CHECK roster. Stamped by
 * the {@link com.gme.pay.registry.bank.verify.AccountVerificationProvider}
 * seam via the verify endpoint, never operator-typed.
 */
public enum BankVerificationStatus {

    /** No verification has landed yet (the V012 column default). */
    UNVERIFIED,

    /**
     * Verified through KFTC's 계좌실명조회 (account-holder real-name check) —
     * the KR rail; covers {@code bank_country = 'KR'} accounts.
     */
    KFTC_VERIFIED,

    /** Verified against an uploaded bank letter (overseas rails). */
    BANK_LETTER,

    /** Verified by a micro-deposit round-trip (overseas rails). */
    MICRO_DEPOSIT
}
