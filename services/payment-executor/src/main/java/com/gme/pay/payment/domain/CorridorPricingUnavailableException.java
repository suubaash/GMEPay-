package com.gme.pay.payment.domain;

/**
 * Thrown when a corridor cannot be PRICED, so the payment is refused before any side effect — gap
 * T4-1.
 *
 * <p><b>Why this exists.</b> The Nepal corridor used to execute with no FX, no fee and no prefunding:
 * the KRW the wallet collected was handed to the Nepal adapter as if it were NPR. That is not a
 * degraded outcome, it is a ~100x mispricing on every transaction. The structural fix (FX + fee +
 * prefunding + revenue, {@link NepalPaymentService}) needs three inputs GME's business owner must
 * supply — the reference rate, the FX margin and the service fee — and <b>none of them may be
 * defaulted in code</b>: a guessed margin or a copied SENDMN fee is the same class of defect as the
 * pass-through it replaces, only harder to see.
 *
 * <p>So an unpriceable corridor <b>fails CLOSED</b>: no scheme call, no float movement, no
 * transaction row beyond the recorded FAILED attempt. A corridor in this state is not sellable, and
 * saying so on the wire is the honest answer.
 *
 * <p>Two distinct causes, both surfaced as HTTP 503 with a stable code (lib-errors is frozen, so the
 * code rides the exception and is emitted through the {@code ApiError} string ctor — the same pattern
 * {@link LimitCheckUnavailableException} and {@link OperationalGateException} use):
 * <ul>
 *   <li>{@link #CODE_NOT_CONFIGURED} — the margin / fee / rate source is simply not configured.
 *       {@code retryable=false}: retrying changes nothing, an owner must enter the terms.</li>
 *   <li>{@link #CODE_RATE_UNAVAILABLE} — the terms exist but the live reference rate could not be
 *       fetched. {@code retryable=true}: a transient rate-provider outage. Deliberately NOT
 *       substituted with a fallback constant — pricing a cross-border payment off a hardcoded rate
 *       is exactly the smell registered as CFO#11.</li>
 * </ul>
 */
public class CorridorPricingUnavailableException extends PaymentException {

    /** Commercial terms (rate source / margin / fee) are not configured for this corridor. */
    public static final String CODE_NOT_CONFIGURED = "CORRIDOR_PRICING_NOT_CONFIGURED";

    /** Terms exist but the live reference rate could not be established. */
    public static final String CODE_RATE_UNAVAILABLE = "CORRIDOR_RATE_UNAVAILABLE";

    private final String code;
    private final boolean retryable;

    private CorridorPricingUnavailableException(String code, boolean retryable, String detail,
                                               Throwable cause) {
        super(detail, cause);
        this.code = code;
        this.retryable = retryable;
    }

    /**
     * The corridor has no configured commercial terms — an OWNER decision is missing. Not retryable.
     *
     * @param corridor human-readable corridor label, e.g. {@code "NEPAL (KRW→NPR)"}
     * @param what     the missing term, e.g. {@code "FX margin"}
     * @param where    where it must be supplied, e.g. the config-registry endpoint / config key
     */
    public static CorridorPricingUnavailableException notConfigured(String corridor, String what,
                                                                    String where) {
        return new CorridorPricingUnavailableException(CODE_NOT_CONFIGURED, false,
                corridor + " cannot be priced: " + what + " is not configured (" + where + ")."
                        + " Refusing the payment rather than applying a guessed value.", null);
    }

    /** The live reference rate could not be fetched. Retryable; never silently substituted. */
    public static CorridorPricingUnavailableException rateUnavailable(String corridor, String pair,
                                                                      Throwable cause) {
        return new CorridorPricingUnavailableException(CODE_RATE_UNAVAILABLE, true,
                corridor + " cannot be priced: live " + pair + " rate unavailable"
                        + (cause == null ? "" : " (" + cause.getMessage() + ")")
                        + ". Refusing rather than pricing off a fallback constant.", cause);
    }

    /** Stable wire code (not an {@code ErrorCode} member — lib-errors is frozen). */
    public String code() {
        return code;
    }

    /** True only for the transient rate-outage cause. */
    public boolean retryable() {
        return retryable;
    }
}
