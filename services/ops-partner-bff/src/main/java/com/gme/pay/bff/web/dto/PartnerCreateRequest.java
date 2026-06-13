package com.gme.pay.bff.web.dto;

import com.gme.pay.contracts.PartnerCommand;
import com.gme.pay.contracts.PartnerView;
import jakarta.validation.constraints.NotBlank;

import java.math.RoundingMode;

/**
 * Wire shape for {@code POST /v1/admin/partners}. Mirrors the pre-Slice-1
 * Admin UI partner-form fields. The {@code settlementRoundingMode} is the
 * textual {@code java.math.RoundingMode} name (e.g. {@code "HALF_UP"},
 * {@code "DOWN"}); the BFF passes it through to config-registry, which is the
 * source of truth for the partner record.
 *
 * @deprecated Slice 1 DTO collapse — bind to {@link PartnerCommand.CreateDraft}
 * from {@code lib-api-contracts} directly. Retained as an Expand-phase alias
 * so the existing Admin UI form still POSTs the same JSON shape; {@link #toCreateDraft()}
 * adapts to the canonical write payload. New fields land on
 * {@link PartnerCommand.CreateDraft} and {@link PartnerView}, not here.
 */
@Deprecated(forRemoval = true, since = "Slice 1 — see docs/PARTNER_SETUP_PLAN.md")
public record PartnerCreateRequest(
        @NotBlank String partnerId,
        @NotBlank String type,
        @NotBlank String settlementCurrency,
        @NotBlank String settlementRoundingMode
) {

    /**
     * Adapt this BFF request shape to the canonical create-draft command.
     * Identity-step fields ride the canonical payload as {@code null}; the
     * pre-Slice-1 form does not collect them.
     */
    public PartnerCommand.CreateDraft toCreateDraft() {
        return new PartnerCommand.CreateDraft(
                partnerId,
                type == null || type.isBlank() ? null : com.gme.pay.domain.PartnerType.valueOf(type),
                settlementCurrency,
                settlementRoundingMode == null || settlementRoundingMode.isBlank()
                        ? null
                        : RoundingMode.valueOf(settlementRoundingMode),
                null, null, null, null, null, null, null, null, null);
    }
}
