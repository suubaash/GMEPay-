package com.gme.pay.kyb;

import java.time.Instant;
import java.util.List;

/**
 * Outcome of one sanctions / PEP / adverse-media screening run (ADR-009).
 *
 * <p>"Can this partner transact" is NEVER decided by this result alone — the
 * partner's status transition is an ADR-008 4-eyes operator decision made after
 * reviewing the screening plus the risk rating. This record is evidence, not a
 * verdict.
 *
 * <ul>
 *   <li>{@code status} — {@link Status#CLEAR} (no matches),
 *       {@link Status#HIT} (at least one list match above the provider's
 *       confidence threshold), {@link Status#NEEDS_REVIEW} (fuzzy / partial
 *       matches an analyst must disposition).</li>
 *   <li>{@code hits} — the individual list matches; empty on CLEAR.</li>
 *   <li>{@code screenedAt} — provider-side completion instant (UTC). Persisted
 *       to a TIMESTAMP column downstream, so providers should truncate to
 *       microseconds (see {@code PartnerStore.save} discipline).</li>
 *   <li>{@code providerRef} — the vendor's reference for this run (audit /
 *       support correlation; {@code "stub-<hash>"} for {@link StubKybAdapter}).</li>
 * </ul>
 */
public record ScreeningResult(
        Status status,
        List<Hit> hits,
        Instant screenedAt,
        String providerRef) {

    /** Screening disposition roster — mirrors the {@code partner_kyb.screening_status} CHECK. */
    public enum Status {
        /** No matches on any screened list. */
        CLEAR,
        /** At least one confident list match — compliance must review before any activation. */
        HIT,
        /** Fuzzy / partial matches requiring analyst disposition. */
        NEEDS_REVIEW
    }

    /**
     * One list match.
     *
     * <ul>
     *   <li>{@code listName} — which list matched (e.g. {@code OFAC_SDN},
     *       {@code EU_CONSOLIDATED}, {@code KOFIU}).</li>
     *   <li>{@code matchedName} — the name (entity or UBO) that matched.</li>
     *   <li>{@code score} — provider match confidence in [0,1].</li>
     * </ul>
     */
    public record Hit(
            String listName,
            String matchedName,
            double score) {
    }

    /** Null-safe accessor: an absent hit list reads as empty, never {@code null}. */
    public List<Hit> hitList() {
        return hits == null ? List.of() : hits;
    }
}
