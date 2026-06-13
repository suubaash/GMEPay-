package com.gme.pay.bff.web.dto;

import com.gme.pay.contracts.PartnerCommand;
import com.gme.pay.contracts.RuleCommand;

import java.util.List;

/**
 * BFF wire shape for
 * {@code PATCH /v1/admin/partners/draft/{partnerCode}/step-6-rules}
 * (Slice 6 — Commercial Terms, see {@code docs/PARTNER_SETUP_PLAN.md}
 * §"Slice 6"). The URL identifies the partner being mutated; the body carries
 * the FULL desired pricing-rule set (bulk-replace semantics — an empty list
 * clears all rules, see {@link PartnerCommand.UpdateStep6Rules}).
 *
 * <p>Elements bind directly to the canonical {@link RuleCommand} (schemeId,
 * direction, mA, mB, serviceChargeUsd). Margins and money are
 * {@link java.math.BigDecimal} carried as decimal STRINGS on the wire per
 * {@code docs/MONEY_CONVENTION.md}.
 *
 * <p>Mirrors {@link PartnerCommand.UpdateStep6Rules}; adapter
 * {@link #toUpdateStep6Rules()} converts to the canonical write payload before
 * the BFF calls config-registry — the same seam discipline as
 * {@link DraftPartnerStep4Request} / {@link DraftPartnerStep5Request}.
 */
public record DraftPartnerStep6RulesRequest(List<RuleCommand> rules) {

    /** Adapt to the canonical write surface {@code lib-api-contracts} exposes. */
    public PartnerCommand.UpdateStep6Rules toUpdateStep6Rules() {
        return new PartnerCommand.UpdateStep6Rules(rules);
    }
}
