package com.gme.pay.bff.web.dto;

import com.gme.pay.contracts.PartnerCommand;

import java.time.LocalTime;

/**
 * BFF wire shape for
 * {@code PATCH /v1/admin/partners/draft/{partnerCode}/step-4-settlement}
 * (Slice 4 — Banking &amp; Settlement, the settlement panel half of step 4;
 * the bank-account rows ride {@link DraftPartnerStep4Request}). The URL
 * identifies the partner being mutated; the body carries the FULL desired
 * settlement panel state (full-state replace — see
 * {@link PartnerCommand.UpdateStep4Settlement} for field semantics and
 * defaults).
 *
 * <p>Mirrors {@link PartnerCommand.UpdateStep4Settlement}; adapter
 * {@link #toUpdateStep4Settlement()} converts to the canonical write payload
 * before the BFF calls config-registry — the same seam discipline as
 * {@link DraftPartnerStep3Request}.
 */
public record DraftPartnerStep4SettlementRequest(
        Integer cycleTPlusN,
        LocalTime cutoffTime,
        String cutoffTimezone,
        String settlementMethod) {

    /** Adapt to the canonical write surface {@code lib-api-contracts} exposes. */
    public PartnerCommand.UpdateStep4Settlement toUpdateStep4Settlement() {
        return new PartnerCommand.UpdateStep4Settlement(
                cycleTPlusN,
                cutoffTime,
                cutoffTimezone,
                settlementMethod);
    }
}
