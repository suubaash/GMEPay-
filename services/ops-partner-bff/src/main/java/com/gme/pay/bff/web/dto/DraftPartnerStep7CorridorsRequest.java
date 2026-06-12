package com.gme.pay.bff.web.dto;

import com.gme.pay.contracts.PartnerCommand;
import com.gme.pay.contracts.PartnerCorridorCommand;

import java.util.List;

/**
 * BFF wire shape for
 * {@code PATCH /v1/admin/partners/draft/{partnerCode}/step-7/corridors}
 * (Slice 7 — Schemes &amp; Corridors, see {@code docs/PARTNER_SETUP_PLAN.md}
 * §"Slice 7"). The URL identifies the partner being mutated; the body carries
 * the FULL desired corridor set (bulk-replace semantics — an empty list clears
 * all corridor rows, see {@link PartnerCommand.UpdateStep7Corridors}).
 *
 * <p>Elements bind directly to the canonical {@link PartnerCorridorCommand}
 * (srcCountry, srcCcy, dstCountry, dstCcy, goLiveDate, isActive). The same
 * direct-binding choice as {@link DraftPartnerStep6RulesRequest}'s
 * {@code RuleCommand} elements.
 *
 * <p>Mirrors {@link PartnerCommand.UpdateStep7Corridors}; adapter
 * {@link #toUpdateStep7Corridors()} converts to the canonical write payload
 * before the BFF calls config-registry — the same seam discipline as
 * {@link DraftPartnerStep6RulesRequest}.
 */
public record DraftPartnerStep7CorridorsRequest(List<PartnerCorridorCommand> corridors) {

    /** Adapt to the canonical write surface {@code lib-api-contracts} exposes. */
    public PartnerCommand.UpdateStep7Corridors toUpdateStep7Corridors() {
        return new PartnerCommand.UpdateStep7Corridors(corridors);
    }
}
