package com.gme.pay.bff.web.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.gme.pay.contracts.PartnerCommand;

import java.math.BigDecimal;

/**
 * BFF wire shape for
 * {@code PATCH /v1/admin/partners/draft/{partnerCode}/step-5}
 * (Slice 5 — Prefunding, see {@code docs/PARTNER_SETUP_PLAN.md} §"Slice 5 —
 * Prefunding"). The URL identifies the partner being mutated; the body carries
 * the FULL desired prefunding panel state (full-state replace — see
 * {@link PartnerCommand.UpdateStep5} for field semantics and defaults).
 *
 * <p>Money fields are {@link BigDecimal} carried as decimal STRINGS on the
 * wire per {@code docs/MONEY_CONVENTION.md} (the {@code @JsonFormat} pins the
 * serialized shape; Jackson accepts strings on the way in regardless).
 *
 * <p>Mirrors {@link PartnerCommand.UpdateStep5}; adapter
 * {@link #toUpdateStep5()} converts to the canonical write payload before the
 * BFF calls config-registry — the same seam discipline as
 * {@link DraftPartnerStep4SettlementRequest}.
 */
public record DraftPartnerStep5Request(
        String fundingModel,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal openingBalanceUsd,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal lowBalanceThresholdUsd,
        Boolean alertTier70,
        Boolean alertTier85,
        Boolean alertTier95,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal creditLimitUsd,
        Boolean autoSuspendOnBreach,
        Long floatTopUpBankAccountId,
        String topUpReferencePattern,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal collateralAmountUsd) {

    /** Adapt to the canonical write surface {@code lib-api-contracts} exposes. */
    public PartnerCommand.UpdateStep5 toUpdateStep5() {
        return new PartnerCommand.UpdateStep5(
                fundingModel,
                openingBalanceUsd,
                lowBalanceThresholdUsd,
                alertTier70,
                alertTier85,
                alertTier95,
                creditLimitUsd,
                autoSuspendOnBreach,
                floatTopUpBankAccountId,
                topUpReferencePattern,
                collateralAmountUsd);
    }
}
