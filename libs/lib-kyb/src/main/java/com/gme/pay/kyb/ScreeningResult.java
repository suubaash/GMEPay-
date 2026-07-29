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
 *       matches an analyst must disposition), or
 *       {@link Status#NOT_SCREENED_NO_PROVIDER} (no authoritative provider ran —
 *       nothing was screened).</li>
 *   <li>{@code hits} — the individual list matches; empty on CLEAR.</li>
 *   <li>{@code screenedAt} — provider-side completion instant (UTC). Persisted
 *       to a TIMESTAMP column downstream, so providers should truncate to
 *       microseconds (see {@code PartnerStore.save} discipline).</li>
 *   <li>{@code providerRef} — the vendor's reference for this run (audit /
 *       support correlation; {@code "stub-<hash>"} for {@link StubKybAdapter}).</li>
 *   <li>{@code provenance} — WHO produced this and whether they are an authority
 *       ({@link ScreeningProvenance}). Never {@code null}: an absent provenance
 *       is normalised to {@link ScreeningProvenance#unknown()}, which is
 *       non-authoritative.</li>
 * </ul>
 *
 * <h2>A non-authoritative CLEAR cannot exist (gap T1-4)</h2>
 *
 * <p>The compact constructor <b>coerces</b> {@link Status#CLEAR} to
 * {@link Status#NOT_SCREENED_NO_PROVIDER} whenever the provenance is not
 * authoritative. Coercion rather than rejection because this type is also a
 * wire DTO: a payload from an older / mis-wired producer must be made honest,
 * not turned into a 500 that hides it. The consequence is the point of the fix —
 * no database column, API response, event or screen can ever receive a "clear
 * sanctions screening" that no screening provider produced. Conservative
 * dispositions ({@link Status#HIT}, {@link Status#NEEDS_REVIEW}) are preserved
 * as-is: they fail closed already, and a stub-staged HIT is useful in demos.
 */
public record ScreeningResult(
        Status status,
        List<Hit> hits,
        Instant screenedAt,
        String providerRef,
        ScreeningProvenance provenance) {

    /** Screening disposition roster — mirrors the {@code partner_kyb.screening_status} CHECK. */
    public enum Status {
        /**
         * No matches on any screened list. Reachable ONLY with authoritative
         * provenance — see the class javadoc.
         */
        CLEAR,
        /** At least one confident list match — compliance must review before any activation. */
        HIT,
        /** Fuzzy / partial matches requiring analyst disposition. */
        NEEDS_REVIEW,
        /**
         * NOTHING WAS SCREENED: no authoritative provider produced this result.
         * The terminal honest state while ADR-014's vendor is unavailable — it is
         * NOT a clean result and must never be read as one. An activation
         * pre-condition is not satisfied by it.
         */
        NOT_SCREENED_NO_PROVIDER
    }

    public ScreeningResult {
        // Absence of provenance is never authority.
        if (provenance == null) {
            provenance = ScreeningProvenance.unknown();
        }
        if (status == Status.CLEAR && !provenance.authoritative()) {
            status = Status.NOT_SCREENED_NO_PROVIDER;
        }
    }

    /**
     * Provenance-less legacy form — retained so historical call shapes keep
     * compiling; the run is recorded as {@link ScreeningProvenance#unknown()}
     * and therefore cannot report CLEAR.
     */
    public ScreeningResult(Status status, List<Hit> hits, Instant screenedAt, String providerRef) {
        this(status, hits, screenedAt, providerRef, ScreeningProvenance.unknown());
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

    /** {@code true} only when a real screening provider produced this result. */
    public boolean authoritative() {
        return provenance != null && provenance.authoritative();
    }

    /**
     * {@code true} when this run actually screened the subject. Callers gating on
     * "has this partner been screened" must test this (or {@link #authoritative()}),
     * never merely {@code status != HIT}.
     */
    public boolean screeningPerformed() {
        return authoritative() && status != Status.NOT_SCREENED_NO_PROVIDER;
    }

    /** The provenance caveat, or {@code null} on an authoritative run. */
    public String caveat() {
        return provenance == null ? ScreeningProvenance.UNKNOWN_CAVEAT : provenance.caveat();
    }
}
