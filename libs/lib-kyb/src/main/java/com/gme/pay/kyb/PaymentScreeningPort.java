package com.gme.pay.kyb;

/**
 * The seam a real sanctions/PEP screening provider plugs into on the <b>payment</b> path — gap
 * <b>T5-3</b>.
 *
 * <p>ADR-009's {@link KybProvider} is the ONBOARDING port: it screens a partner entity and its UBOs
 * once, at activation, and its verdict is reviewed by a human before the partner goes LIVE (gap T1-4
 * made that verdict refuse to masquerade as done). This port is the other half — the per-transaction
 * counterparty check that the CISO audit found does not exist anywhere: a repo-wide grep of
 * {@code payment-executor}, {@code smart-router} and {@code transaction-mgmt} for
 * sanctions/screening/watchlist/PEP returned no consumer, only the monetary "AML cumulative cap".
 *
 * <h2>What this interface deliberately does NOT define</h2>
 * <p>No rules, no thresholds, no risk scores, no list roster, no fuzzy-match tolerance, no
 * structuring/velocity heuristics. Those are policy and vendor decisions owned by compliance, and a
 * plausible-looking rule set invented here would be worse than none — it would read, in code review
 * and in a regulator pack, as a control that exists. What is defined is the shape of the question and
 * the shape of an honest answer.
 *
 * <h2>Implementations must never fabricate a clean result</h2>
 * <p>The return type is the same {@link ScreeningResult} the KYB path uses, so the T1-4 guarantee is
 * inherited for free: a {@link ScreeningResult.Status#CLEAR} whose {@link ScreeningProvenance} is not
 * authoritative is <b>coerced</b> by the record's own constructor to
 * {@link ScreeningResult.Status#NOT_SCREENED_NO_PROVIDER}. An implementation therefore cannot report
 * "clean" by accident, by omission, or by copying the wrong constructor — only a vendor adapter that
 * calls {@link ScreeningProvenance#vendor(String)} can make that claim, and it can only do so having
 * declared who it is.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li><b>Must not throw.</b> This is called on the live payment path before any float moves. An
 *       implementation that cannot answer returns a {@code NOT_SCREENED_NO_PROVIDER} result carrying
 *       the failure as its caveat; the gate classifies that as
 *       {@link UnscreenedReason#PROVIDER_ERROR}. A throwing implementation is still contained by the
 *       gate, but it forfeits the ability to explain itself.</li>
 *   <li><b>Must not block indefinitely.</b> A provider call sits in front of a customer-facing
 *       payment; implementations own their own timeout and answer within it.</li>
 *   <li><b>Must not log the subject's attribute values.</b> Names and dates of birth are the PII this
 *       platform does not yet encrypt at rest (gap T5-5); use
 *       {@link PaymentScreeningSubject#attributeSummary()}.</li>
 * </ul>
 */
public interface PaymentScreeningPort {

    /**
     * Screen one party of one payment against the provider's sanctions / PEP / adverse-media sources.
     *
     * @param subject the party to screen; never {@code null}. May be unscreenable
     *                ({@link PaymentScreeningSubject#screenable()} {@code == false}) — the gate filters
     *                those out before calling, but an implementation must still answer honestly rather
     *                than optimistically if handed one.
     * @return the outcome; never {@code null}. {@link ScreeningResult.Status#CLEAR} is permitted ONLY
     *         with authoritative provenance (the record enforces this).
     */
    ScreeningResult screen(PaymentScreeningSubject subject);

    /**
     * A stable id for this provider, used in the coverage record and the startup banner so an operator
     * can see WHICH provider is (or is not) answering.
     */
    String providerId();

    /**
     * {@code true} only when this implementation actually consults sanctions/PEP sources. The default
     * is {@code false} — a new implementation is presumed non-authoritative until it says otherwise,
     * because the failure mode being designed out is an implementation that is treated as a control by
     * default.
     */
    default boolean authoritative() {
        return false;
    }
}
