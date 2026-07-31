package com.gme.pay.kyb;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * A named human's attestation that they performed a sanctions / PEP screening BY HAND, following
 * a compliance-signed written procedure — the interim screening authority chosen by the owner for
 * gap T1-4 while the ADR-014 vendor is unavailable.
 *
 * <h2>Why this is an authority and the stub is not</h2>
 *
 * <p>{@link StubKybAdapter} keyword-matches the subject's own names against nothing. No list is
 * consulted, so its clean branch is not a screening and {@link ScreeningResult} coerces it to
 * {@link ScreeningResult.Status#NOT_SCREENED_NO_PROVIDER}. That coercion is unchanged and must
 * stay unchanged.
 *
 * <p>A manual screening is different in kind, not in degree: a person actually opened the
 * sanctions and PEP sources named in {@link #sourcesConsulted()}, actually searched the subject's
 * names, and is personally accountable for the answer. What made that indistinguishable from the
 * stub before was not the screening — it was that the platform recorded no evidence of it. This
 * record IS that evidence, and every field is load-bearing:
 *
 * <ul>
 *   <li>{@link #attesterActorId()} — <b>who</b>. Must be a verified human in the T5-1
 *       {@code AuditActors} vocabulary (bare, unprefixed — an attested subject). This type
 *       cannot check that itself: lib-kyb is deliberately dependency-light and, more to the
 *       point, only the service that terminated the credential can know whether a name was
 *       verified. The check therefore lives at the single write path that mints an attestation
 *       ({@code KybService.recordManualScreeningAttestation}, via
 *       {@code AuditActors.requireAttestedHuman}); what this record enforces is that the field is
 *       present and is not one of the shapes that are structurally NOT a person.</li>
 *   <li>{@link #attestedAt()} — <b>when</b>. A screening is a point-in-time statement about
 *       list contents that change daily; an attestation with no instant cannot be aged out or
 *       re-run on a schedule.</li>
 *   <li>{@link #sopDocumentRef()} + {@link #sopVersion()} — <b>under what procedure</b>. Without
 *       these the attestation says only "I checked", which is exactly the unfalsifiable claim
 *       T1-4 exists to remove. The activation gate refuses an attestation missing either.</li>
 *   <li>{@link #sourcesConsulted()} — <b>what was checked</b>. Free text supplied by the
 *       attester. Deliberately NOT a validated enum of list names: this codebase does not know
 *       which lists GME's procedure names, and inventing a roster (OFAC SDN, EU consolidated,
 *       KoFIU…) would put list names into the record that nobody consulted — the same class of
 *       lie as the stub's {@code CLEAR}.</li>
 * </ul>
 *
 * <h2>What it deliberately is NOT</h2>
 *
 * <p>It is not a substitute for the vendor and does not claim to be. It is authoritative in the
 * narrow sense that matters to the activation gate — a screening was performed by an accountable
 * party against named sources — and it stays visibly distinguishable from a vendor screening
 * everywhere downstream via {@link ScreeningResult.Status#CLEAR_MANUAL_ATTESTATION}, which is
 * never flattened to a bare {@link ScreeningResult.Status#CLEAR}.
 *
 * @param attesterActorId  the verified human who performed and is accountable for the screening.
 * @param attestedAt       when the attestation was made (UTC, truncated to microseconds so the
 *                         stored TIMESTAMP equals the in-memory value on PostgreSQL and H2).
 * @param sopDocumentRef   reference to the compliance-signed SOP document that was followed
 *                         (document id / title / URI — whatever compliance controls it by).
 * @param sopVersion       the version or revision of that document the attester followed.
 * @param sourcesConsulted the lists / registers / sources actually consulted, as the attester
 *                         describes them. Free text on purpose (see above).
 */
public record ManualScreeningAttestation(
        String attesterActorId,
        Instant attestedAt,
        String sopDocumentRef,
        String sopVersion,
        String sourcesConsulted) {

    /** Column width of {@code partner_kyb.manual_attester_actor_id} (matches {@code audit_log.actor_id}). */
    public static final int MAX_ATTESTER_LEN = 64;

    /** Column width of {@code partner_kyb.manual_sop_document_ref}. */
    public static final int MAX_SOP_DOCUMENT_REF_LEN = 128;

    /** Column width of {@code partner_kyb.manual_sop_version}. */
    public static final int MAX_SOP_VERSION_LEN = 32;

    /** Column width of {@code partner_kyb.manual_sources_consulted}. */
    public static final int MAX_SOURCES_CONSULTED_LEN = 2000;

    public ManualScreeningAttestation {
        attesterActorId = requireText(attesterActorId, "attesterActorId",
                MAX_ATTESTER_LEN,
                "the human who performed the manual screening must be named — an unattributed"
                        + " attestation is not an authority");
        if (isStructurallyNotAPerson(attesterActorId)) {
            // A last-line structural check. The real verification happens at the write path
            // (AuditActors.requireAttestedHuman); this catches a caller that skipped it, and it
            // is why the reserved shapes are spelled out rather than assumed away.
            throw new IllegalArgumentException(
                    "attesterActorId '" + attesterActorId + "' is a platform principal or an"
                            + " unproven claim, not a verified human — a manual screening"
                            + " attestation must name a person who can be held to it");
        }
        if (attestedAt == null) {
            throw new IllegalArgumentException(
                    "attestedAt is required: a screening is a point-in-time statement about list"
                            + " contents, so an attestation with no instant can never be aged out");
        }
        attestedAt = attestedAt.truncatedTo(ChronoUnit.MICROS);
        sopDocumentRef = requireText(sopDocumentRef, "sopDocumentRef",
                MAX_SOP_DOCUMENT_REF_LEN,
                "the compliance-signed SOP document that was followed must be referenced — without"
                        + " it the attestation is an unfalsifiable \"I checked\"");
        sopVersion = requireText(sopVersion, "sopVersion",
                MAX_SOP_VERSION_LEN,
                "the SOP version the attester followed must be stated — procedures change, and an"
                        + " attestation that does not say which revision it followed cannot be"
                        + " reviewed");
        sourcesConsulted = requireText(sourcesConsulted, "sourcesConsulted",
                MAX_SOURCES_CONSULTED_LEN,
                "the attester must state which lists / registers / sources were actually consulted");
    }

    /**
     * The reserved actor namespaces from {@code AuditActors} that can never be the human behind an
     * attestation, spelled out here because lib-kyb does not depend on lib-audit (see the class
     * javadoc). Kept in sync by {@code AuditActorsManualAttestationTest} in config-registry, which
     * drives real {@code AuditActors} values through this constructor.
     */
    private static boolean isStructurallyNotAPerson(String actorId) {
        return actorId.startsWith("system:")
                || actorId.startsWith("svc:")
                || actorId.startsWith("unverified:")
                || actorId.equals("unattributed")
                || actorId.equalsIgnoreCase("system");
    }

    private static String requireText(String value, String field, int maxLen, String why) {
        String v = value == null ? null : value.trim();
        if (v == null || v.isEmpty()) {
            throw new IllegalArgumentException(field + " is required on a manual screening"
                    + " attestation: " + why);
        }
        if (v.length() > maxLen) {
            // Refuse rather than clamp. Truncating "what was checked" would silently shorten the
            // record of the control that was performed, and truncating the SOP reference could
            // point at a different document.
            throw new IllegalArgumentException(field + " must be at most " + maxLen
                    + " characters (was " + v.length() + ") — it is persisted verbatim and is not"
                    + " truncated, because a shortened record of what was screened is a different"
                    + " claim");
        }
        return v;
    }
}
