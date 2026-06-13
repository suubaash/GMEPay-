package com.gme.pay.bff.web.dto;

import com.gme.pay.contracts.AddressCommand;
import com.gme.pay.contracts.PartnerCommand;
import com.gme.pay.domain.PartnerType;

import java.math.RoundingMode;

/**
 * BFF wire shape for {@code POST /v1/admin/partners/draft} — the Admin UI
 * wizard's "Start a new partner" submission. The Admin UI sees this shape;
 * the BFF adapts to the canonical {@link PartnerCommand.CreateDraft} carried by
 * {@code lib-api-contracts} before calling config-registry.
 *
 * <p>Field-for-field mirror of {@link PartnerCommand.CreateDraft}. The duplicate
 * exists deliberately:
 * <ul>
 *   <li>The Admin UI binds to BFF JSON shapes (per Slice 1's "BFF surface is
 *       front-end-stable" contract). Changing the upstream contract should not
 *       force an Admin UI redeploy if we can adapt at this seam.</li>
 *   <li>Later slices may add UI-only fields (e.g. {@code keepDraftPrivate})
 *       that don't belong on the registry contract.</li>
 * </ul>
 *
 * <p>{@link #toCreateDraft()} performs the conversion to the canonical command.
 * Every field is nullable so the wizard can save partial Step-1 progress.
 */
public record DraftPartnerRequest(
        String partnerCode,
        PartnerType type,
        String settlementCurrency,
        RoundingMode settlementRoundingMode,
        String legalNameLocal,
        String legalNameRomanized,
        String taxId,
        String taxIdType,
        String countryOfIncorporation,
        String legalForm,
        AddressCommand registeredAddress,
        AddressCommand operatingAddress,
        String lei) {

    /** Adapt to the canonical write surface {@code lib-api-contracts} exposes. */
    public PartnerCommand.CreateDraft toCreateDraft() {
        return new PartnerCommand.CreateDraft(
                partnerCode,
                type,
                settlementCurrency,
                settlementRoundingMode,
                legalNameLocal,
                legalNameRomanized,
                taxId,
                taxIdType,
                countryOfIncorporation,
                legalForm,
                registeredAddress,
                operatingAddress,
                lei);
    }
}
