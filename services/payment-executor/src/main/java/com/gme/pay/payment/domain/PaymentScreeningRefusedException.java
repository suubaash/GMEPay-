package com.gme.pay.payment.domain;

import com.gme.pay.kyb.UnscreenedReason;

/**
 * Raised when a NEW payment is refused for a sanctions/PEP screening reason — gap <b>T5-3</b>.
 *
 * <p>Two distinct causes, two stable codes, because they are not the same event and must not be
 * triaged the same way:
 *
 * <ul>
 *   <li>{@link #SCREENING_UNAVAILABLE} — nothing screened the parties and the operator has chosen
 *       {@code gmepay.screening.fail-closed=true}, so the platform refuses rather than accept an
 *       unscreened payment. This is a <b>configuration posture</b>, not a finding about the customer.
 *       It is <b>off by default</b>; see {@code PaymentScreeningGate} for why the default here is the
 *       opposite of the KYB activation gate's.</li>
 *   <li>{@link #SANCTIONS_HIT} — a real, authoritative provider returned a match or a
 *       needs-review disposition for a party on this payment. This one is enforced <b>always</b>,
 *       regardless of the fail-closed flag: honouring a provider's own adverse verdict is not a policy
 *       choice, and a platform that carried on would be knowingly processing a flagged payment.</li>
 * </ul>
 *
 * <p><b>What has NOT happened when this is thrown.</b> The gate runs at the very start of a new
 * authorization, composed into {@code OperationalGate.checkNewAuthorization}: no float reserved or
 * deducted, no transaction row created, no scheme call, no ledger posting, no event. Exactly the
 * pre-side-effect position of T4-2's limit rejections and T3-6's {@code SCHEME_CLOSED}.
 *
 * <p><b>Non-retryable.</b> Both codes carry {@code retryable=false}. Neither can be cured by an
 * immediate retry: a missing vendor needs a procurement decision, and a sanctions match needs a human
 * disposition. Marking either retryable would turn a compliance refusal into a retry storm against the
 * same refusal.
 *
 * <p>Carries a STABLE canonical code string surfaced verbatim via {@code ApiError(code, …)} rather than
 * an {@code ErrorCode} enum member, following {@link OperationalGateException},
 * {@link LimitCheckUnavailableException} and {@link SchemeOperationNotSupportedException} — lib-errors
 * was outside this change's ownership. Promoting both codes to {@code ErrorCode} members is a listed
 * follow-up.
 *
 * <p>Extends {@link RuntimeException} rather than {@link PaymentException} deliberately: existing
 * {@code catch (PaymentException)} sites treat their catch as a payment-layer/scheme refusal and several
 * feed the {@code DECLINE_SPIKE} monitor. A screening refusal is neither a decline by a scheme nor a
 * fault to be spike-monitored — the whole point of the coverage counter and the ops alert is that it has
 * its own, differently-shaped visibility.
 */
public class PaymentScreeningRefusedException extends RuntimeException {

    /**
     * Nothing screened the parties and {@code gmepay.screening.fail-closed} is on. Non-retryable: the
     * cure is a screening provider, not another attempt.
     */
    public static final String SCREENING_UNAVAILABLE = "SANCTIONS_SCREENING_UNAVAILABLE";

    /**
     * An authoritative provider returned a HIT or NEEDS_REVIEW for a party on this payment.
     * Non-retryable: it needs an analyst disposition, not a retry.
     */
    public static final String SANCTIONS_HIT = "SANCTIONS_SCREENING_HIT";

    private final String code;

    private PaymentScreeningRefusedException(String code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * Fail-closed refusal: the platform will not accept a payment it cannot screen.
     *
     * <p>The message names the CAUSE, because the four {@link UnscreenedReason}s have four different
     * owners and an operator reading a 422 must know whether to call the vendor, the partner-integration
     * team or the on-call.
     */
    public static PaymentScreeningRefusedException unavailable(UnscreenedReason reason, String detail) {
        return new PaymentScreeningRefusedException(SCREENING_UNAVAILABLE,
                "payment refused: sanctions/PEP screening did not happen (" + reason.name() + " — "
                        + reason.description() + "). 'gmepay.screening.fail-closed' is enabled, so"
                        + " unscreened payments are not accepted"
                        + (detail == null || detail.isBlank() ? "" : ". " + detail));
    }

    /**
     * A provider's adverse verdict. The message carries the disposition and the provider, never the
     * matched name or list detail — that is investigation material for the case queue, not something to
     * echo to an API caller (tipping-off risk, and PII this platform does not encrypt at rest).
     */
    public static PaymentScreeningRefusedException hit(String disposition, String providerId) {
        return new PaymentScreeningRefusedException(SANCTIONS_HIT,
                "payment refused: sanctions/PEP screening returned " + disposition + " for a party on"
                        + " this payment (provider '" + providerId + "'). It must be dispositioned by"
                        + " compliance before this payment can proceed.");
    }

    /** The stable canonical error code (see the constants above). */
    public String code() {
        return code;
    }

    /** Both codes are non-retryable — see the class javadoc. */
    public boolean retryable() {
        return false;
    }
}
