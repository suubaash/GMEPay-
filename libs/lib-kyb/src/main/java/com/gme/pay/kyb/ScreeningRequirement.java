package com.gme.pay.kyb;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * WHICH payment parties an owner has decided must be screened before a payment may proceed — gap
 * <b>T5-3</b>.
 *
 * <h2>This is configuration, and it is EMPTY by default</h2>
 *
 * <p>The set ships empty, and an empty set means <b>screening is not required of anybody</b>, which
 * means {@link TransactionScreeningPolicy} refuses nothing and the payment path behaves exactly as it
 * did before this seam existed. That default is deliberate and is the reason this type exists at all
 * rather than a bare boolean: shipping a seam must not change what the platform does to live traffic,
 * because the moment it does, the change is a business decision that was made by a code merge.
 *
 * <p>Turning a party on here is a <b>compliance decision with a revenue consequence</b>. With no
 * authoritative provider wired (the platform's current state — see {@link NoProviderPaymentScreeningPort}),
 * requiring a party means every payment involving that party is refused, because
 * {@link TransactionScreeningPolicy} defaults to refusing what it cannot screen. Requiring
 * {@link PaymentParty#PAYER} is stronger still: neither payment contract carries an originator name
 * (see {@link PaymentScreeningSubject#screenable()}), so a payer requirement refuses every payment even
 * after a vendor is bought, until the API contracts carry a screenable identity. Both of those are
 * facts an owner should be told before flipping a flag, not discovered in production.
 *
 * <h2>What this type deliberately is not</h2>
 *
 * <p>It is not a rule engine, a risk tier, a corridor matrix or an amount threshold. "Screen payers on
 * cross-border payments above X" is a policy statement with four invented parameters in it; this type
 * expresses only the one thing that can be stated without inventing anything — which roles the
 * institution has decided it owes a screening duty to.
 *
 * @param requiredParties the roles that must carry a completed screening; never {@code null}, empty by
 *                        default, and defensively copied into an immutable {@link EnumSet}
 */
public record ScreeningRequirement(Set<PaymentParty> requiredParties) {

    public ScreeningRequirement {
        if (requiredParties == null || requiredParties.isEmpty()) {
            requiredParties = Set.of();
        } else {
            // Copy: a caller must not be able to widen the requirement after construction, and a
            // requirement that grew at runtime would make an audited refusal unexplainable.
            requiredParties = Set.copyOf(EnumSet.copyOf(requiredParties));
        }
    }

    /**
     * The default and the shipped state: nothing is required, so nothing is refused. Named rather than
     * spelled {@code new ScreeningRequirement(Set.of())} at call sites so the default is greppable.
     */
    public static ScreeningRequirement none() {
        return new ScreeningRequirement(Set.of());
    }

    /** An explicit requirement over the given roles. */
    public static ScreeningRequirement of(PaymentParty... parties) {
        if (parties == null || parties.length == 0) {
            return none();
        }
        return new ScreeningRequirement(Set.of(parties));
    }

    /**
     * Bind a requirement from configuration strings (property binding / an env var).
     *
     * <p>An unrecognised role is <b>rejected</b>, not skipped. A typo that silently produced an empty
     * requirement would be indistinguishable from "screening is off" — the operator would believe a
     * control was armed while nothing was required of anybody, which is the precise class of quiet
     * failure this whole gap is about. Failing at startup is the cheap version of that discovery.
     *
     * @param raw role names, case-insensitive; {@code null}/empty yields {@link #none()}
     * @throws IllegalArgumentException on any unrecognised role name
     */
    public static ScreeningRequirement parse(Collection<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return none();
        }
        EnumSet<PaymentParty> parsed = EnumSet.noneOf(PaymentParty.class);
        for (String token : raw) {
            if (token == null || token.isBlank()) {
                continue;
            }
            String name = token.trim().toUpperCase(Locale.ROOT);
            try {
                parsed.add(PaymentParty.valueOf(name));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "unknown payment party '" + token.trim() + "' in the screening requirement;"
                        + " valid values are " + EnumSet.allOf(PaymentParty.class)
                        + ". Refusing to start with a requirement that would silently screen nobody.");
            }
        }
        return new ScreeningRequirement(parsed);
    }

    /** {@code true} when this party must carry a completed screening before a payment may proceed. */
    public boolean requires(PaymentParty party) {
        return party != null && requiredParties.contains(party);
    }

    /** {@code true} in the default state — no party is required, so no payment can be refused here. */
    public boolean isEmpty() {
        return requiredParties.isEmpty();
    }

    /** Stable, sorted description for the startup banner and the audit payload. */
    public String describe() {
        if (requiredParties.isEmpty()) {
            return "none (no party requires screening — the payment path is unchanged)";
        }
        return EnumSet.copyOf(requiredParties).toString();
    }
}
