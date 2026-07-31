package com.gme.pay.registry.kyb;

import com.gme.pay.kyb.ScreeningProvenance;
import java.time.Instant;

/**
 * The full provenance of a partner's stored sanctions screening — WHO produced the verdict,
 * whether they are an authority, and (for a manual run) the human attestation behind it.
 *
 * <h2>Why a dedicated read model exists (gap T1-4, requirement 5)</h2>
 *
 * <p>The canonical {@code KybView} lives in {@code libs/lib-api-contracts}, which this change does
 * not own, so provenance cannot become a set of new fields on it. That leaves two ways for the
 * distinction between "a vendor screened this partner" and "a compliance officer screened it by
 * hand under SOP X v3" to reach a screen, and this change uses BOTH rather than picking one:
 *
 * <ol>
 *   <li>the {@code screeningStatus} value itself is different
 *       ({@code CLEAR} vs {@code CLEAR_MANUAL_ATTESTATION}), so even a consumer that reads nothing
 *       but the status string cannot flatten the two together — that is the guarantee, and it holds
 *       without this DTO;</li>
 *   <li>this endpoint carries the DETAIL a reviewer needs once they can see the difference: which
 *       SOP, which version, who signed it, when, and what they say they checked.</li>
 * </ol>
 *
 * <p>Belt and braces on purpose. (1) alone would tell an operator that a manual attestation exists
 * without letting them read it; (2) alone would be silently lost by every consumer that forgets to
 * call it.
 *
 * @param screeningStatus     the stored verdict, verbatim ({@code null} before any run).
 * @param providerId          who produced it — {@code stub} / {@code unknown} /
 *                            {@value ScreeningProvenance#MANUAL_ATTESTATION_PROVIDER_ID} / a vendor id.
 * @param authoritative       whether that producer is an authority whose verdict may satisfy the
 *                            activation sanctions pre-condition.
 * @param caveat              why it is not authoritative ({@code null} when it is).
 * @param screenedAt          provider-side completion instant.
 * @param providerRef         the producer's reference for the run.
 * @param manuallyAttested    {@code true} when the authority is an attested manual screening.
 * @param manualAttestation   the attestation detail, or {@code null} for every non-manual run.
 * @param satisfiesActivation whether this row, as stored, satisfies the activation sanctions
 *                            pre-condition. Derived here rather than left to the caller so a UI
 *                            cannot draw its own (wrong) conclusion from a green-looking status.
 * @param interpretation      one sentence stating what this provenance does and does not mean.
 *                            Present for the same reason the T5-3 coverage endpoint carries one:
 *                            a bare status is read as reassurance, and "NOT_SCREENED_NO_PROVIDER"
 *                            needs to say so in words an operator will act on.
 */
public record ScreeningProvenanceView(
        String screeningStatus,
        String providerId,
        boolean authoritative,
        String caveat,
        Instant screenedAt,
        String providerRef,
        boolean manuallyAttested,
        ManualAttestationView manualAttestation,
        boolean satisfiesActivation,
        String interpretation) {

    /**
     * The persisted manual-SOP attestation (V045). Every field is non-null on a well-formed row —
     * {@code complete} exists because a row from before V045, an older dump or a future migration
     * can be missing one, and that must be VISIBLE rather than rendered as a valid attestation
     * with blanks.
     *
     * @param attesterActorId  the verified human who performed the screening.
     * @param attestedAt       when they attested.
     * @param sopDocumentRef   the compliance-signed SOP document followed.
     * @param sopVersion       its revision.
     * @param sourcesConsulted what they say they checked.
     * @param complete         whether all five fields are present.
     */
    public record ManualAttestationView(
            String attesterActorId,
            Instant attestedAt,
            String sopDocumentRef,
            String sopVersion,
            String sourcesConsulted,
            boolean complete) {
    }

    /** Project a stored KYB row into its provenance view. */
    public static ScreeningProvenanceView from(KybEntity kyb) {
        boolean manual = kyb.isManuallyAttestedScreening();
        ManualAttestationView attestation = manual
                ? new ManualAttestationView(
                        kyb.getManualAttesterActorId(),
                        kyb.getManualAttestedAt(),
                        kyb.getManualSopDocumentRef(),
                        kyb.getManualSopVersion(),
                        kyb.getManualSourcesConsulted(),
                        kyb.hasCompleteManualAttestation())
                : null;
        boolean satisfies = kyb.hasAuthoritativeScreening()
                && (!manual || kyb.hasCompleteManualAttestation());
        return new ScreeningProvenanceView(
                kyb.getScreeningStatus(),
                kyb.getScreeningProviderId(),
                Boolean.TRUE.equals(kyb.getScreeningAuthoritative()),
                kyb.getScreeningCaveat(),
                kyb.getScreenedAt(),
                kyb.getScreeningProviderRef(),
                manual,
                attestation,
                satisfies,
                interpret(kyb, manual, satisfies));
    }

    private static String interpret(KybEntity kyb, boolean manual, boolean satisfies) {
        if (kyb.getScreeningStatus() == null) {
            return "No sanctions screening has been recorded for this partner. Activation is"
                    + " refused (SANCTIONS_NOT_SCREENED).";
        }
        if (KybEntity.SCREENING_NOT_PERFORMED.equals(kyb.getScreeningStatus())) {
            return "NOTHING WAS SCREENED. A producer that consults no sanctions, PEP or"
                    + " adverse-media source returned this — it is not a clean result and must not"
                    + " be read as one. Activation is refused (SANCTIONS_NOT_SCREENED).";
        }
        if (manual && !satisfies) {
            return "A manual screening is claimed but its attestation is INCOMPLETE, so it names"
                    + " nobody who can be held to it. Activation is refused"
                    + " (SANCTIONS_MANUAL_ATTESTATION_INCOMPLETE). Record the attestation again in"
                    + " full.";
        }
        if (manual) {
            return "Screened MANUALLY by " + kyb.getManualAttesterActorId() + " under SOP "
                    + kyb.getManualSopDocumentRef() + " " + kyb.getManualSopVersion()
                    + ". This is a human control under a compliance-signed procedure, NOT a"
                    + " vendor screening against automated list feeds, and it does not include"
                    + " ongoing rescreening against list changes.";
        }
        if (!kyb.hasAuthoritativeScreening()) {
            return "The stored screening is NOT AUTHORITATIVE, so activation is refused"
                    + (kyb.getScreeningCaveat() == null ? "." : ". " + kyb.getScreeningCaveat());
        }
        return "Screened by the '" + kyb.getScreeningProviderId() + "' provider. Verdict: "
                + kyb.getScreeningStatus() + ".";
    }
}
