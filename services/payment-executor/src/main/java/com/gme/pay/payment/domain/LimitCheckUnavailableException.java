package com.gme.pay.payment.domain;

/**
 * Thrown when a partner HAS a regulatory limit configured but the platform cannot evaluate it — no
 * USD basis for the corridor amount (live rate unavailable for a non-KRW currency), or the
 * cumulative-usage ledger in prefunding could not be reached.
 *
 * <p><b>Fail CLOSED.</b> The alternative — proceeding because the check could not run — is exactly
 * the T4-2 hole: an unbounded payment on a capped partner. A configured cap that cannot be evaluated
 * refuses the payment before any side effect (no float movement, no scheme call).
 *
 * <p>Distinct from {@link TransactionLimitExceededException} / {@link CumulativeLimitExceededException}
 * (an actual breach): this is a transient inability to decide, so it is surfaced as <b>retryable</b>
 * with the stable code {@code LIMIT_CHECK_UNAVAILABLE}. lib-errors is frozen, so the code rides the
 * exception and is emitted via the {@code ApiError} string ctor — the same pattern
 * {@link OperationalGateException} and {@link SchemeOperationNotSupportedException} use.
 *
 * <p>Note the deliberate asymmetry with {@code PartnerConfigClient.resolveLimits}, which stays
 * fail-SOFT (a config-registry outage yields "no limits configured"): that pre-existing risk window
 * is unchanged by T4-2. This exception covers the case where limits WERE resolved and are therefore
 * known to apply.
 */
public class LimitCheckUnavailableException extends PaymentException {

    /** Stable wire code (not an {@code ErrorCode} member — lib-errors is frozen). */
    public static final String CODE = "LIMIT_CHECK_UNAVAILABLE";

    public LimitCheckUnavailableException(String detail) {
        super(detail);
    }

    public LimitCheckUnavailableException(String detail, Throwable cause) {
        super(detail, cause);
    }

    public String code() {
        return CODE;
    }
}
