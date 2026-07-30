package com.gme.pay.kyb;

import java.util.Locale;
import java.util.Set;

/**
 * The fail-open / fail-closed decision on the payment path, made explicitly and once — gap
 * <b>T5-3</b>.
 *
 * <h2>The dilemma, stated plainly</h2>
 *
 * <p>A payment path that blocks whenever a screening provider is unavailable halts revenue across
 * every corridor. A payment path that proceeds silently when it could not screen is an AML finding.
 * There is no implementation that is safe on both counts, so the only defensible thing to do is make
 * the choice visible, configurable, and safe by default rather than let it be an accident of which
 * branch someone wrote first.
 *
 * <p>This class resolves it in two layers, and the split is the whole design:
 *
 * <ol>
 *   <li><b>Is screening REQUIRED of this party?</b> — {@link ScreeningRequirement}, which is
 *       <b>empty by default</b>. In the shipped configuration the answer is always no, so this policy
 *       refuses nothing and the payment path behaves exactly as it did before the seam existed. No
 *       test expectation and no live transaction changes because this code merged.</li>
 *   <li><b>Given that it IS required, what happens when it cannot be done?</b> — <b>refuse</b>. This
 *       is the safe default and it is not configurable to "proceed" in production; see the override
 *       below. Requiring a screening and then proceeding without one is not a posture, it is a
 *       requirement that does not exist.</li>
 * </ol>
 *
 * <h2>The override is deliberately awkward</h2>
 *
 * <p>{@code allowUnavailableOverride} lets a party be required while an unavailable provider is
 * tolerated — the shape a team needs in a sandbox where the vendor is not wired but the code path must
 * still be exercised end to end. It is constrained three ways:
 *
 * <ul>
 *   <li>it is <b>rejected outright in production</b> — the constructor throws, so a service configured
 *       this way does not start, rather than starting and quietly permitting;</li>
 *   <li>an <b>unknown or blank environment counts as production</b>. A deployment that failed to say
 *       what it is does not get the benefit of the doubt, because the environments that lack an
 *       environment label are exactly the ones nobody is watching;</li>
 *   <li>every use of it produces {@link Posture#PROCEED_UNAVAILABLE_OVERRIDDEN}, whose
 *       {@link Decision#mustAudit()} is {@code true}. A payment that moved money without the screening
 *       its own configuration demanded is an audit row, always. It never looks like a clean pass.</li>
 * </ul>
 *
 * <h2>What this class deliberately does NOT decide</h2>
 *
 * <p>No thresholds, no amount bands, no corridor rules, no risk scores, no match tolerance, no
 * structuring or velocity heuristics. Those are compliance policy. This class contains exactly one
 * judgement — "a required check that did not happen is a refusal" — which is not a policy choice but
 * the definition of the word "required".
 *
 * <p>Instances are immutable and thread-safe; the payment path may hold one as a singleton.
 */
public final class TransactionScreeningPolicy {

    /**
     * Environment names treated as production. Anything NOT on this list — including a blank, a typo
     * and a name nobody thought of — is also treated as production by {@link #isProduction(String)};
     * this list only says which names are known to be safe to relax in.
     */
    private static final Set<String> NON_PRODUCTION_ENVIRONMENTS =
            Set.of("local", "dev", "development", "test", "ci", "sandbox", "uat", "staging", "stage");

    private final ScreeningRequirement requirement;
    private final boolean allowUnavailableOverride;
    private final String environment;

    /**
     * @param requirement              which parties must be screened; {@code null} is read as
     *                                 {@link ScreeningRequirement#none()} (the safe default — an absent
     *                                 requirement requires nothing and therefore refuses nothing)
     * @param allowUnavailableOverride tolerate a required-but-unavailable screening instead of refusing.
     *                                 Non-production only.
     * @param environment              the deployment environment name; blank/unknown is treated as
     *                                 production
     * @throws IllegalStateException when the override is armed in a production (or unlabelled)
     *                               environment — the service must not start
     */
    public TransactionScreeningPolicy(ScreeningRequirement requirement,
                                      boolean allowUnavailableOverride,
                                      String environment) {
        this.requirement = requirement == null ? ScreeningRequirement.none() : requirement;
        this.allowUnavailableOverride = allowUnavailableOverride;
        this.environment = environment == null ? "" : environment.trim();
        if (allowUnavailableOverride && isProduction(this.environment)) {
            throw new IllegalStateException(
                    "screening fail-open override is armed in environment '"
                    + (this.environment.isEmpty() ? "<unset>" : this.environment)
                    + "', which is treated as production. Proceeding with payments whose required"
                    + " sanctions/PEP screening did not happen is a compliance decision that cannot be"
                    + " taken by configuration in production (gap T5-3). Either clear the override or"
                    + " clear the screening requirement — refusing to start rather than permit.");
        }
    }

    /**
     * The shipped default: nothing is required, nothing is overridden, so nothing is refused. Use this
     * anywhere a policy is needed but none is configured.
     */
    public static TransactionScreeningPolicy off() {
        return new TransactionScreeningPolicy(ScreeningRequirement.none(), false, "");
    }

    /**
     * {@code true} when {@code environment} is NOT a recognised non-production name. Unknown counts as
     * production on purpose — see the class javadoc.
     */
    public static boolean isProduction(String environment) {
        if (environment == null || environment.isBlank()) {
            return true;
        }
        return !NON_PRODUCTION_ENVIRONMENTS.contains(environment.trim().toLowerCase(Locale.ROOT));
    }

    public ScreeningRequirement requirement() {
        return requirement;
    }

    /** {@code true} only when the non-production fail-open override is armed. */
    public boolean overrideArmed() {
        return allowUnavailableOverride;
    }

    public String environment() {
        return environment;
    }

    /**
     * {@code true} when this policy cannot refuse anything — the shipped state. Callers use it to skip
     * the whole screening call path, and the startup banner uses it to say so out loud.
     */
    public boolean inert() {
        return requirement.isEmpty();
    }

    /**
     * Apply the policy to one party's screening outcome.
     *
     * @param party  the role screened; {@code null} is treated as not required
     * @param result the provider's answer. {@code null} is treated as an unavailable provider — an
     *               absent answer is never a pass.
     * @return the posture and the reason for it; never {@code null}
     */
    public Decision decide(PaymentParty party, ScreeningResult result) {
        if (!requirement.requires(party)) {
            return new Decision(party, Posture.PROCEED_NOT_REQUIRED,
                    "screening is not required for " + party + " (configured requirement: "
                            + requirement.describe() + ")",
                    result);
        }
        if (result == null) {
            return unavailable(party, null,
                    "the screening provider returned no result at all");
        }

        // Conservative dispositions refuse regardless of provenance and regardless of the override.
        // A staged/non-authoritative HIT still means somebody's list logic said stop; the override
        // governs what happens when we know NOTHING, never what happens when we know something bad.
        switch (result.status()) {
            case HIT:
                return new Decision(party, Posture.REFUSE_SCREENING_HIT,
                        "the screening provider reported a list match for " + party
                                + " (" + result.hitList().size() + " hit(s))",
                        result);
            case NEEDS_REVIEW:
                return new Decision(party, Posture.REFUSE_SCREENING_NEEDS_REVIEW,
                        "the screening provider reported a possible match for " + party
                                + " requiring analyst disposition",
                        result);
            case CLEAR:
                // Reachable only with authoritative provenance: ScreeningResult's own constructor
                // coerces a non-authoritative CLEAR to NOT_SCREENED_NO_PROVIDER before it gets here.
                // The guard below is therefore unreachable-by-construction and kept as a belt: if the
                // record's invariant were ever weakened, this must not become a silent pass.
                if (!result.screeningPerformed()) {
                    return unavailable(party, result,
                            "a CLEAR result arrived without authoritative provenance");
                }
                return new Decision(party, Posture.PROCEED_SCREENED_CLEAR,
                        "screened clear by " + result.provenance().providerId(), result);
            case NOT_SCREENED_NO_PROVIDER:
            default:
                return unavailable(party, result, result.caveat());
        }
    }

    private Decision unavailable(PaymentParty party, ScreeningResult result, String detail) {
        String because = detail == null || detail.isBlank()
                ? "no authoritative screening was performed"
                : detail;
        if (allowUnavailableOverride) {
            return new Decision(party, Posture.PROCEED_UNAVAILABLE_OVERRIDDEN,
                    "PROCEEDING WITHOUT A REQUIRED SCREENING under the non-production override"
                            + " (environment '" + environment + "'): " + because,
                    result);
        }
        return new Decision(party, Posture.REFUSE_SCREENING_UNAVAILABLE,
                "screening is required for " + party + " but could not be performed: " + because,
                result);
    }

    /**
     * What the payment path must do about one party.
     *
     * <p>Read {@link #refuses()} rather than comparing enum constants: new postures may be added, and
     * a caller that tested {@code == REFUSE_SCREENING_UNAVAILABLE} would silently start permitting the
     * new one.
     */
    public enum Posture {

        /** Screening is not required for this party — the shipped default for every party. */
        PROCEED_NOT_REQUIRED(false, false),

        /** An authoritative provider screened this party and found nothing. */
        PROCEED_SCREENED_CLEAR(false, false),

        /**
         * Required, not performed, and permitted anyway by the non-production override. Money moves
         * without the check the configuration demands, so this ALWAYS audits and is never reportable as
         * a clean screening.
         */
        PROCEED_UNAVAILABLE_OVERRIDDEN(false, true),

        /** Required and not performed: the safe default. */
        REFUSE_SCREENING_UNAVAILABLE(true, true),

        /** A list match. Refused regardless of configuration. */
        REFUSE_SCREENING_HIT(true, true),

        /** A possible match awaiting analyst disposition. Refused regardless of configuration. */
        REFUSE_SCREENING_NEEDS_REVIEW(true, true);

        private final boolean refuses;
        private final boolean mustAudit;

        Posture(boolean refuses, boolean mustAudit) {
            this.refuses = refuses;
            this.mustAudit = mustAudit;
        }

        /** {@code true} when the payment must not proceed. */
        public boolean refuses() {
            return refuses;
        }

        /**
         * {@code true} when this posture must produce an audit row even though the payment proceeded.
         * The only proceeding posture that must audit is the override — the whole point of allowing it
         * is that its use leaves a trail.
         */
        public boolean mustAudit() {
            return mustAudit;
        }
    }

    /**
     * One party's posture plus the human-readable reason for it. The reason is carried into the
     * persisted evidence row, the audit payload and the refusal returned to the caller, so a regulator
     * asking "why was this payment refused" and an operator asking "why is this corridor down" read the
     * same sentence.
     */
    public record Decision(PaymentParty party, Posture posture, String reason, ScreeningResult result) {

        public Decision {
            if (posture == null) {
                throw new IllegalArgumentException("posture is required on a screening decision");
            }
        }

        /** {@code true} when the payment must not proceed. */
        public boolean refuses() {
            return posture.refuses();
        }

        /** {@code true} when this decision must be written to the audit trail. */
        public boolean mustAudit() {
            return posture.mustAudit();
        }
    }
}
