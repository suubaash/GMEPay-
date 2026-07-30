package com.gme.pay.kyb;

/**
 * The role a {@link PaymentScreeningSubject} plays on one payment — gap <b>T5-3</b>.
 *
 * <p>Sanctions/PEP screening is a duty owed per <b>party</b>, not per transaction: a regulator asks
 * "was the originator screened, and was the beneficiary screened", and a platform that screened one
 * of them has not discharged the obligation. Keeping the role on the subject is therefore what makes
 * the coverage question answerable at all — {@code UnscreenedPaymentCounter} keys its rows by role, so
 * "we screen nobody" and "we screen the merchant but never the payer" are different, visible states
 * rather than one undifferentiated "unscreened" number.
 *
 * <p>This enum deliberately does NOT include the PARTNER (the licensed institution sending the
 * traffic). That subject is screened at onboarding on a completely different path — config-registry's
 * KYB wizard plus the {@code ActivationGateService} {@code SANCTIONS_NOT_SCREENED} pre-condition
 * (gap T1-4) — and conflating an onboarding-time entity check with a per-payment counterparty check is
 * precisely the confusion T5-3 exists to remove.
 */
public enum PaymentParty {

    /**
     * The natural or legal person whose funds are moving — the originator / remitter / payer. On this
     * platform the payer reaches payment-executor as an OPAQUE reference only
     * ({@code customer_ref} on {@code POST /v1/payments/authorize}, {@code userRef} on the wallet
     * {@code POST /v1/pay}); no name, date of birth, nationality or address is ever transmitted. See
     * {@link PaymentScreeningSubject#screenable()}.
     */
    PAYER,

    /**
     * The party receiving the funds — the beneficiary / payee. For the QR corridors this is the
     * merchant behind the scanned code.
     */
    BENEFICIARY,

    /**
     * The merchant accepting the payment, when it is a distinct subject from the beneficiary (an
     * acquiring relationship rather than the payee itself).
     */
    MERCHANT
}
