package com.gme.pay.contracts;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The single source of truth for the partner lifecycle FSM (Slice 8 / ADR-011):
 * which {@link PartnerStatus} → {@link PartnerStatus} edges are legal, which of
 * them require 4-eyes approval (ADR-008), and the human-readable guard each
 * edge carries.
 *
 * <h2>Why this lives in lib-api-contracts (not lib-domain)</h2>
 *
 * <p>The plan doc suggests {@code com.gme.pay.domain.partner.fsm}, but
 * {@code lib-api-contracts} {@code api}-depends on {@code lib-domain}, so a
 * lib-domain class cannot reference {@link PartnerStatus} (which has lived in
 * this module since Slice 1) without a dependency cycle. The table therefore
 * sits next to the enum it constrains. It is pure data + lookups — no Spring,
 * no I/O — which is exactly this module's contract.
 *
 * <h2>The full lifecycle</h2>
 *
 * <pre>
 *   DRAFT → ONBOARDING → KYB_PENDING ⇄ (back to ONBOARDING on "needs more info")
 *         → KYB_APPROVED → CONTRACT_SIGNED → SANDBOX → UAT
 *         → LIVE ⇄ SUSPENDED, LIVE/SUSPENDED → TERMINATED
 * </pre>
 *
 * <p>Edges into/out of {@code LIVE} (and into {@code TERMINATED}) are 4-eyes:
 * the operator's request is parked as a {@code change_request} (PROPOSED) and a
 * SECOND operator must approve before the transition is applied. All earlier
 * edges are single-operator wizard moves, guarded by their step pre-conditions.
 *
 * <p>{@code TERMINATED} is terminal — no outbound edges. Keep this table in
 * lock-step with the V025 {@code ck_partners_status} CHECK roster.
 */
public final class PartnerStatusTransitionTable {

    /**
     * One legal FSM edge.
     *
     * @param from            source status.
     * @param to              target status.
     * @param requiresFourEyes whether the edge may only be applied through an
     *                        approved {@code change_request} (ADR-008).
     * @param guard           human-readable pre-condition the edge carries.
     */
    public record Transition(PartnerStatus from, PartnerStatus to,
                             boolean requiresFourEyes, String guard) {
    }

    /** Every legal edge, in forward-flow order (the order ADR-011 documents). */
    private static final List<Transition> TRANSITIONS = List.of(
            new Transition(PartnerStatus.DRAFT, PartnerStatus.ONBOARDING,
                    false, "any operator"),
            new Transition(PartnerStatus.ONBOARDING, PartnerStatus.KYB_PENDING,
                    false, "any operator, only after step-3 saved"),
            new Transition(PartnerStatus.KYB_PENDING, PartnerStatus.KYB_APPROVED,
                    false, "any operator, only after KYB GREEN/AMBER with override notes"),
            new Transition(PartnerStatus.KYB_PENDING, PartnerStatus.ONBOARDING,
                    false, "any operator, KYB requires more info"),
            new Transition(PartnerStatus.KYB_APPROVED, PartnerStatus.CONTRACT_SIGNED,
                    false, "any operator, contract dates set"),
            new Transition(PartnerStatus.CONTRACT_SIGNED, PartnerStatus.SANDBOX,
                    false, "any operator, sandbox creds issued"),
            new Transition(PartnerStatus.SANDBOX, PartnerStatus.UAT,
                    false, "any operator, UAT smoke test passed"),
            new Transition(PartnerStatus.UAT, PartnerStatus.LIVE,
                    true, "4-eyes, activation gate must pass"),
            new Transition(PartnerStatus.LIVE, PartnerStatus.SUSPENDED,
                    true, "4-eyes, suspension_reason required"),
            new Transition(PartnerStatus.SUSPENDED, PartnerStatus.LIVE,
                    true, "4-eyes, reactivation"),
            new Transition(PartnerStatus.LIVE, PartnerStatus.TERMINATED,
                    true, "4-eyes, termination_reason required"),
            new Transition(PartnerStatus.SUSPENDED, PartnerStatus.TERMINATED,
                    true, "4-eyes, termination_reason required"));

    /** (from → (to → edge)) index for O(1) lookups. */
    private static final Map<PartnerStatus, Map<PartnerStatus, Transition>> BY_FROM = index();

    private static Map<PartnerStatus, Map<PartnerStatus, Transition>> index() {
        Map<PartnerStatus, Map<PartnerStatus, Transition>> byFrom =
                new EnumMap<>(PartnerStatus.class);
        for (Transition t : TRANSITIONS) {
            byFrom.computeIfAbsent(t.from(), k -> new EnumMap<>(PartnerStatus.class))
                    .put(t.to(), t);
        }
        return byFrom;
    }

    private PartnerStatusTransitionTable() {
        // static lookup table
    }

    /** Every legal edge, forward-flow order. The returned list is immutable. */
    public static List<Transition> all() {
        return TRANSITIONS;
    }

    /** True iff {@code from → to} is a legal edge of the FSM. */
    public static boolean isAllowed(PartnerStatus from, PartnerStatus to) {
        return find(from, to).isPresent();
    }

    /**
     * True iff {@code from → to} is a legal edge AND requires a second pair of
     * eyes (an approved {@code change_request}) before it may be applied.
     * Returns {@code false} for illegal edges — call {@link #isAllowed} first
     * when the distinction matters.
     */
    public static boolean requiresFourEyes(PartnerStatus from, PartnerStatus to) {
        return find(from, to).map(Transition::requiresFourEyes).orElse(false);
    }

    /** The edge record for {@code from → to}, or empty when the edge is illegal. */
    public static Optional<Transition> find(PartnerStatus from, PartnerStatus to) {
        Map<PartnerStatus, Transition> targets = BY_FROM.get(from);
        return targets == null ? Optional.empty() : Optional.ofNullable(targets.get(to));
    }

    /**
     * Every status reachable in ONE step from {@code from}. Empty for terminal
     * states ({@link PartnerStatus#TERMINATED}). The returned set is a copy.
     */
    public static Set<PartnerStatus> targetsFrom(PartnerStatus from) {
        Map<PartnerStatus, Transition> targets = BY_FROM.get(from);
        return targets == null
                ? EnumSet.noneOf(PartnerStatus.class)
                : EnumSet.copyOf(targets.keySet());
    }
}
