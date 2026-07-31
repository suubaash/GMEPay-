package com.gme.pay.kyb;

/**
 * WHO produced a {@link ScreeningResult}, and whether that producer is an
 * authority whose verdict may be presented as a completed sanctions/PEP check
 * (gap T1-4).
 *
 * <h2>Why this exists</h2>
 *
 * <p>Until the Octa Solution sandbox credentials land (ADR-014) the only
 * {@link KybProvider} that answers is {@link StubKybAdapter} — keyword matching
 * on the subject's own names. No list is consulted: not OFAC SDN, not the EU
 * consolidated list, not KoFIU, no PEP register, no adverse media. A result from
 * it is therefore <b>not a screening</b>, and the danger is not that it is wrong
 * — it is that a clean-looking result is indistinguishable from a real one three
 * hops downstream, in a database column, on a screen, or in a regulator pack.
 *
 * <p>Every {@code ScreeningResult} therefore carries its provenance, and the
 * result type itself refuses to hold a {@link ScreeningResult.Status#CLEAR}
 * whose provenance is not authoritative (see
 * {@link ScreeningResult#ScreeningResult}). "Clean" is a claim only a real
 * provider is allowed to make.
 *
 * @param providerId  stable identifier of the producer — {@value #STUB_PROVIDER_ID}
 *                    for the in-process stub, {@value #UNKNOWN_PROVIDER_ID} when
 *                    the producer did not declare itself, otherwise the vendor's
 *                    own id (e.g. {@code octa}). Never blank.
 * @param authoritative {@code true} only for a real screening provider that
 *                    actually consulted sanctions/PEP/adverse-media sources.
 * @param caveat      why this provenance is not authoritative — mandatory when
 *                    {@code authoritative} is {@code false}, and mandatorily
 *                    {@code null} when it is {@code true} (a real screening has
 *                    nothing to caveat). Carried through to the persisted row and
 *                    the API response so the limitation travels with the verdict.
 * @param attestation the human attestation backing a {@value #MANUAL_ATTESTATION_PROVIDER_ID}
 *                    run (gap T1-4, owner decision 2026-07-28) — mandatory for that
 *                    provider id and mandatorily {@code null} for every other. This is
 *                    what makes the manual authority real rather than a second way to
 *                    claim authority: the only way to construct a provenance that says
 *                    "a human screened this" is to supply the evidence of who, when and
 *                    under which SOP. {@code null} on every vendor / stub / absent-provider
 *                    provenance.
 */
public record ScreeningProvenance(
        String providerId,
        boolean authoritative,
        String caveat,
        ManualScreeningAttestation attestation) {

    /** Producer id of lib-kyb's in-process {@link StubKybAdapter}. */
    public static final String STUB_PROVIDER_ID = "stub";

    /** Producer id used when a result arrived without declaring its producer. */
    public static final String UNKNOWN_PROVIDER_ID = "unknown";

    /**
     * Producer id meaning <b>no screening provider is wired at all</b> — nothing ran, not even the
     * stub (gap T5-3, the transaction path). Distinct from {@link #UNKNOWN_PROVIDER_ID}, which means
     * something ran but did not say what it was.
     */
    public static final String NO_PROVIDER_ID = "none";

    /**
     * Producer id of an <b>attested manual screening</b> performed by a human under a
     * compliance-signed SOP (gap T1-4 owner decision). Authoritative, but only ever
     * constructible together with a {@link ManualScreeningAttestation} — see the
     * {@code attestation} component and {@link #manualAttestation(ManualScreeningAttestation)}.
     */
    public static final String MANUAL_ATTESTATION_PROVIDER_ID = "manual-sop";

    /** The stub's standing caveat — the exact sentence that must reach the operator. */
    public static final String STUB_CAVEAT =
            "NOT A SANCTIONS SCREENING: produced in-process by StubKybAdapter, which keyword-matches"
            + " the subject's own names. No sanctions, PEP or adverse-media source was consulted"
            + " (no KYB vendor is configured — ADR-014, Octa sandbox credentials pending).";

    /** Caveat used when provenance is absent — absence is never treated as authority. */
    public static final String UNKNOWN_CAVEAT =
            "PROVENANCE ABSENT: the producer of this screening result did not declare itself, so it"
            + " cannot be presented as a completed check. Treated as non-authoritative.";

    public ScreeningProvenance {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("providerId is required on screening provenance");
        }
        if (authoritative && (STUB_PROVIDER_ID.equals(providerId) || NO_PROVIDER_ID.equals(providerId))) {
            // Structural, not stylistic: the one thing nobody may ever construct is a stub — or an
            // absent-provider — result that claims authority.
            throw new IllegalArgumentException(
                    "the '" + providerId + "' provider can never be authoritative");
        }
        if (authoritative && caveat != null && !caveat.isBlank()) {
            throw new IllegalArgumentException(
                    "an authoritative screening carries no caveat, was: " + caveat);
        }
        if (!authoritative && (caveat == null || caveat.isBlank())) {
            throw new IllegalArgumentException(
                    "a non-authoritative screening must state why (caveat is required)");
        }
        if (authoritative) {
            caveat = null;
        }
        // ---- The manual-attestation authority (T1-4) is evidence-bound, both ways -----------
        if (MANUAL_ATTESTATION_PROVIDER_ID.equals(providerId)) {
            if (attestation == null) {
                throw new IllegalArgumentException(
                        "the '" + MANUAL_ATTESTATION_PROVIDER_ID + "' provider may only be used"
                                + " together with a ManualScreeningAttestation — an unattested"
                                + " manual screening is exactly the unfalsifiable claim gap T1-4"
                                + " removed, so it cannot be constructed");
            }
            if (!authoritative) {
                throw new IllegalArgumentException(
                        "a manual screening backed by an attestation IS authoritative; a"
                                + " non-authoritative one would be a contradiction. Use"
                                + " nonAuthoritative(providerId, caveat) with a different provider"
                                + " id to record a degraded run.");
            }
        } else if (attestation != null) {
            throw new IllegalArgumentException(
                    "a ManualScreeningAttestation may only accompany provider id '"
                            + MANUAL_ATTESTATION_PROVIDER_ID + "', was: " + providerId
                            + " — attaching human attestation evidence to a machine run would make"
                            + " the two indistinguishable downstream");
        }
    }

    /**
     * Three-component form for every provenance that carries no human attestation (the stub, an
     * undeclared producer, an absent provider, a vendor). Retained as the natural constructor so
     * the T1-4 call sites and any 3-field JSON payload keep working unchanged.
     */
    public ScreeningProvenance(String providerId, boolean authoritative, String caveat) {
        this(providerId, authoritative, caveat, null);
    }

    /** Provenance of a {@link StubKybAdapter} run — never authoritative. */
    public static ScreeningProvenance stub() {
        return new ScreeningProvenance(STUB_PROVIDER_ID, false, STUB_CAVEAT);
    }

    /** Provenance of a result that arrived without any — never authoritative. */
    public static ScreeningProvenance unknown() {
        return new ScreeningProvenance(UNKNOWN_PROVIDER_ID, false, UNKNOWN_CAVEAT);
    }

    /**
     * Provenance of a sanctions / PEP screening a named human performed by hand under a
     * compliance-signed SOP (gap T1-4). Authoritative — and the ONLY way to mint that authority
     * is to hand over the evidence, because the compact constructor rejects
     * {@value #MANUAL_ATTESTATION_PROVIDER_ID} without it.
     *
     * <p>Note what this does <b>not</b> do: it does not weaken the stub coercion, it does not make
     * {@link ScreeningResult.Status#CLEAR} reachable for a manual run (that is coerced to
     * {@link ScreeningResult.Status#CLEAR_MANUAL_ATTESTATION} so the provenance survives every
     * downstream hop), and it does not claim parity with a vendor.
     *
     * @throws IllegalArgumentException when {@code attestation} is {@code null}.
     */
    public static ScreeningProvenance manualAttestation(ManualScreeningAttestation attestation) {
        return new ScreeningProvenance(MANUAL_ATTESTATION_PROVIDER_ID, true, null, attestation);
    }

    /** {@code true} when this provenance is an attested manual screening (T1-4). */
    public boolean manuallyAttested() {
        return MANUAL_ATTESTATION_PROVIDER_ID.equals(providerId) && attestation != null;
    }

    /**
     * Provenance for "no provider is configured, so nothing was screened" (gap T5-3) — never
     * authoritative. The caveat must state which subject/party was not screened, so it is supplied by
     * the caller rather than being a constant here.
     *
     * @param caveat why nothing was screened; must be non-blank
     */
    public static ScreeningProvenance noProvider(String caveat) {
        return new ScreeningProvenance(NO_PROVIDER_ID, false, caveat);
    }

    /**
     * Provenance of a real vendor screening. Reachable only from a vendor
     * adapter that actually called the vendor; there is deliberately no way to
     * mint this for the stub (the compact constructor rejects it).
     */
    public static ScreeningProvenance vendor(String providerId) {
        if (STUB_PROVIDER_ID.equals(providerId) || UNKNOWN_PROVIDER_ID.equals(providerId)
                || NO_PROVIDER_ID.equals(providerId)) {
            throw new IllegalArgumentException(
                    "'" + providerId + "' is a reserved non-authoritative provider id");
        }
        if (MANUAL_ATTESTATION_PROVIDER_ID.equals(providerId)) {
            // Otherwise this would be the back door: a "vendor" called manual-sop, authoritative,
            // with no attestation behind it — i.e. the whole control bypassed by a string.
            throw new IllegalArgumentException(
                    "'" + MANUAL_ATTESTATION_PROVIDER_ID + "' is reserved for an attested manual"
                            + " screening — use manualAttestation(attestation)");
        }
        return new ScreeningProvenance(providerId, true, null);
    }

    /**
     * A non-authoritative provider other than the stub (e.g. a vendor adapter
     * that answered from a degraded/cached path). Requires a caveat.
     */
    public static ScreeningProvenance nonAuthoritative(String providerId, String caveat) {
        if (MANUAL_ATTESTATION_PROVIDER_ID.equals(providerId)) {
            throw new IllegalArgumentException(
                    "'" + MANUAL_ATTESTATION_PROVIDER_ID + "' is reserved for an attested manual"
                            + " screening, which is authoritative by construction — a"
                            + " non-authoritative one cannot exist");
        }
        return new ScreeningProvenance(providerId, false, caveat);
    }
}
