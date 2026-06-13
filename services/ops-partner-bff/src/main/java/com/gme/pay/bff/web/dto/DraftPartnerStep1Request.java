package com.gme.pay.bff.web.dto;

import com.gme.pay.contracts.AddressCommand;
import com.gme.pay.contracts.PartnerCommand;
import com.gme.pay.domain.PartnerType;

import java.math.RoundingMode;

/**
 * BFF wire shape for {@code PATCH /v1/admin/partners/draft/{partnerCode}/step-1}.
 * Same shape as {@link DraftPartnerRequest} minus {@code partnerCode} — the URL
 * identifies the partner being mutated, not the body.
 *
 * <p>Mirrors {@link PartnerCommand.UpdateStep1}; adapter {@link #toUpdateStep1()}
 * converts to the canonical write payload before the BFF calls config-registry.
 */
public record DraftPartnerStep1Request(
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
    public PartnerCommand.UpdateStep1 toUpdateStep1() {
        return new PartnerCommand.UpdateStep1(
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
