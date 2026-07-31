package com.gme.pay.registry.kyb;

/**
 * Request payload for recording a MANUAL sanctions/PEP screening attestation on a partner
 * (gap T1-4, owner decision 2026-07-28).
 *
 * <h2>Why this is a config-registry type and not a {@code KybCommand} member</h2>
 *
 * <p>{@code KybCommand} lives in {@code libs/lib-api-contracts}, which this change does not own.
 * Adding a member there would also make the attestation look like another operator-editable
 * step-3 field, which it is not: step-3 is a full-state replace the operator may repeat freely,
 * whereas an attestation is an append-only assertion by a named human. Keeping the shape here
 * keeps those two write semantics visibly separate.
 *
 * <h2>What is NOT on this record, deliberately</h2>
 *
 * <p>There is no attester field. The attester is never client input — it is the actor the request
 * itself proves ({@code AuditActorResolver}), which is the entire point of the T5-1 work that
 * removed the spoofable {@code X-Actor} default. A caller supplying a name here would be able to
 * sign a compliance assertion in somebody else's name.
 *
 * <p>There is no {@code attestedAt} either: the instant is the server's, so an attestation cannot
 * be back-dated to before the SOP version it claims to have followed existed.
 *
 * @param outcome          what the manual screening found: {@code CLEAR} (no matches — recorded as
 *                         {@code CLEAR_MANUAL_ATTESTATION}), {@code HIT} or {@code NEEDS_REVIEW}.
 *                         {@code NOT_SCREENED_NO_PROVIDER} is rejected: an attestation asserting
 *                         that nothing was screened is not an attestation.
 * @param sopDocumentRef   the compliance-signed SOP document that was followed.
 * @param sopVersion       the revision of that document.
 * @param sourcesConsulted which lists / registers / sources were actually consulted, in the
 *                         attester's own words. Free text — see
 *                         {@link com.gme.pay.kyb.ManualScreeningAttestation}.
 * @param attestation      the attester's explicit assertion. Must be exactly
 *                         {@link #REQUIRED_ASSERTION}: this is a typed confirmation, not a
 *                         checkbox, so a UI cannot default it to true and a scripted caller
 *                         cannot omit the sentence it is agreeing to.
 */
public record ManualAttestationCommand(
        String outcome,
        String sopDocumentRef,
        String sopVersion,
        String sourcesConsulted,
        String attestation) {

    /**
     * The sentence the attester must send verbatim. Kept as a constant so the wording the
     * operator agrees to in the UI, the wording the API requires, and the wording quoted in the
     * SOP document are one string rather than three that can drift apart.
     */
    public static final String REQUIRED_ASSERTION =
            "I performed this sanctions and PEP screening myself, following the SOP named above,"
            + " and I am accountable for the result.";
}
