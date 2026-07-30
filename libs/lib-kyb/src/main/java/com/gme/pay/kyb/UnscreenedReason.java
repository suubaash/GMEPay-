package com.gme.pay.kyb;

/**
 * WHY a payment party was not screened — gap <b>T5-3</b>.
 *
 * <p>The whole point of this enum is that "unscreened" is not one condition, and the four causes have
 * four different owners and four different fixes. Collapsing them into a single boolean is what lets a
 * platform believe it is one vendor contract away from compliance when in fact its payment contracts
 * carry no screenable identity at all.
 *
 * <p>Each member is persisted as a {@code reason_code} on payment-executor's
 * {@code unscreened_payments} coverage table and used as the ops-alert de-duplication key, so a
 * regulator's "how many payments went through unscreened, and why" is answerable in one SQL query per
 * cause rather than inferred from logs.
 */
public enum UnscreenedReason {

    /**
     * No screening provider is configured on the payment path — the default state of this platform.
     * Owner: the compliance/vendor decision (which vendor, which lists, what contract). Nothing in the
     * code can close this.
     */
    NO_PROVIDER("no sanctions/PEP screening provider is configured on the payment path"),

    /**
     * A provider IS wired, but this party arrived with no attribute it could match on — in practice, no
     * name (see {@link PaymentScreeningSubject#screenable()}). Owner: the API contract, i.e. the
     * partner-facing payment request and the wallet request must start carrying the originator's name
     * (and ideally DOB + nationality) before any vendor can screen anybody. This is a
     * <b>contract-and-partner-integration</b> change, not a configuration one, and it is the reason
     * buying a vendor today would not by itself produce screening coverage.
     */
    NO_SUBJECT_IDENTITY("the payment carried no screenable identity for this party (no name)"),

    /**
     * The configured provider failed — unreachable, timed out, threw, or returned something
     * unparseable. Owner: operations. Deliberately its own reason: a vendor outage is a transient
     * incident with an on-call response, whereas {@link #NO_PROVIDER} is a permanent posture, and a
     * counter that mixed them would hide an outage inside a known-zero baseline.
     */
    PROVIDER_ERROR("the configured screening provider failed and produced no verdict"),

    /**
     * The provider answered, but its answer was not authoritative — a degraded/cached path, a stub, or
     * a result with absent provenance. Owner: whoever wired the provider. The result is honest about
     * itself ({@link ScreeningResult} coerces such a CLEAR to
     * {@link ScreeningResult.Status#NOT_SCREENED_NO_PROVIDER}); this reason records that it happened.
     */
    PROVIDER_NOT_AUTHORITATIVE("the provider's verdict was not authoritative and cannot count as a check");

    private final String description;

    UnscreenedReason(String description) {
        this.description = description;
    }

    /** Human-readable cause, used verbatim in the caveat, the WARN line and the ops-alert detail. */
    public String description() {
        return description;
    }
}
