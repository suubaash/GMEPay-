package com.gme.pay.contracts;

import java.time.LocalDate;
import java.util.List;

/**
 * Canonical write surface for a partner's KYB sub-resource (Slice 3 — see
 * {@code docs/PARTNER_SETUP_PLAN.md} §"Slice 3 — KYB"). Follows the
 * {@link PartnerCommand} wrapper convention: one nested record per
 * sub-command, the wrapper dispatches on the non-null field.
 *
 * <p>Slice 3 ships one sub-command; the screening trigger is deliberately NOT
 * a command payload — {@code POST /v1/partners/{code}/kyb/screen} carries no
 * body (the server assembles the screening subject from what it already
 * knows, so a caller can never screen a subject that differs from the stored
 * aggregate).
 */
public record KybCommand(UpdateStep3 updateStep3) {

    /** Convenience constructor for an update-step-3 command. */
    public static KybCommand update(UpdateStep3 update) {
        return new KybCommand(update);
    }

    /**
     * Body for "Save step-3 changes" (KYB) on an already-created draft. The
     * wizard's contract is "send the full step-3 state on every save" — the
     * payload replaces every operator-editable KYB field on the row (a
     * {@code null} field means the operator cleared it), the same
     * full-state-replace discipline as {@link PartnerCommand.UpdateStep1}.
     *
     * <p>Screening fields ({@code screeningStatus} / {@code providerRef} /
     * {@code screenedAt}) are NOT part of this payload: they are written only
     * by the screening run and carried forward across step-3 saves
     * server-side, so a wizard save can never overwrite a screening verdict.
     *
     * <ul>
     *   <li>{@code riskRating} — {@code LOW} | {@code MEDIUM} | {@code HIGH};
     *       optional while drafting (the Slice 8 activation gate requires
     *       it).</li>
     *   <li>{@code riskRationale} — &le; 1000 chars.</li>
     *   <li>{@code nextReviewDate} — periodic CDD review due date.</li>
     *   <li>{@code licenseType} (&le; 50) / {@code licenseNumber} (&le; 50) /
     *       {@code licenseAuthority} (&le; 100) / {@code licenseExpiry}.</li>
     *   <li>{@code uboList} — full declared UBO set (bulk replace, like
     *       contacts); each element validated per {@link UboView}.</li>
     *   <li>{@code cbddqDocId} — vault document id of the Wolfsberg CBDDQ
     *       (ADR-006), once uploaded.</li>
     * </ul>
     */
    public record UpdateStep3(
            String riskRating,
            String riskRationale,
            LocalDate nextReviewDate,
            String licenseType,
            String licenseNumber,
            String licenseAuthority,
            LocalDate licenseExpiry,
            List<UboView> uboList,
            Long cbddqDocId) {
    }
}
