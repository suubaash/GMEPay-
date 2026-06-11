package com.gme.pay.bff.web.dto;

import com.gme.pay.contracts.KybCommand;
import com.gme.pay.contracts.UboView;

import java.time.LocalDate;
import java.util.List;

/**
 * BFF wire shape for {@code PATCH /v1/admin/partners/draft/{partnerCode}/step-3}
 * (Slice 3 — KYB). The URL identifies the partner being mutated; the body
 * carries the FULL desired step-3 state (full-state replace of the
 * operator-editable KYB fields — see {@link KybCommand.UpdateStep3}; screening
 * fields are server-owned and carried forward upstream).
 *
 * <p>Mirrors {@link KybCommand.UpdateStep3}; adapter {@link #toUpdateStep3()}
 * converts to the canonical write payload before the BFF calls
 * config-registry — the same seam discipline as {@link DraftPartnerStep1Request}
 * / {@link DraftPartnerStep2Request}. UBO elements bind directly to the
 * canonical {@link UboView} (name, ownershipPct, isPep, country).
 */
public record DraftPartnerStep3Request(
        String riskRating,
        String riskRationale,
        LocalDate nextReviewDate,
        String licenseType,
        String licenseNumber,
        String licenseAuthority,
        LocalDate licenseExpiry,
        List<UboView> uboList,
        Long cbddqDocId) {

    /** Adapt to the canonical write surface {@code lib-api-contracts} exposes. */
    public KybCommand.UpdateStep3 toUpdateStep3() {
        return new KybCommand.UpdateStep3(
                riskRating,
                riskRationale,
                nextReviewDate,
                licenseType,
                licenseNumber,
                licenseAuthority,
                licenseExpiry,
                uboList,
                cbddqDocId);
    }
}
