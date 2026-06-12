package com.gme.pay.bff.web.dto;

import com.gme.pay.contracts.ContractCommand;
import com.gme.pay.contracts.FeeScheduleCommand;
import com.gme.pay.contracts.FxConfigCommand;
import com.gme.pay.contracts.LimitsCommand;
import com.gme.pay.contracts.PartnerCommand;

import java.util.List;

/**
 * BFF wire shape for
 * {@code PATCH /v1/admin/partners/draft/{partnerCode}/step-6-commercial}
 * (Slice 6 — Commercial Terms, see {@code docs/PARTNER_SETUP_PLAN.md}
 * §"Slice 6"). The URL identifies the partner being mutated; the body carries
 * up to four commercial sections applied ATOMICALLY by config-registry (see
 * {@link PartnerCommand.UpdateStep6Commercial} for the section semantics:
 * non-null = full-state/bulk replace, null = untouched, all-null = 400).
 *
 * <p>Sections bind directly to the canonical commands
 * ({@link FeeScheduleCommand} / {@link FxConfigCommand} /
 * {@link LimitsCommand} / {@link ContractCommand}) — the same direct-binding
 * choice as {@link DraftPartnerStep4Request}'s {@code BankAccountCommand}
 * elements. Money and bps ride as decimal STRINGS on the wire per
 * {@code docs/MONEY_CONVENTION.md}; the 소액해외송금업 caps are enforced
 * server-side in config-registry (mirrored by the stub).
 *
 * <p>Adapter {@link #toUpdateStep6Commercial()} converts to the canonical
 * write payload before the BFF calls config-registry — the same seam
 * discipline as {@link DraftPartnerStep5Request}.
 */
public record DraftPartnerStep6CommercialRequest(
        List<FeeScheduleCommand> feeSchedules,
        FxConfigCommand fxConfig,
        LimitsCommand limits,
        ContractCommand contract) {

    /** Adapt to the canonical write surface {@code lib-api-contracts} exposes. */
    public PartnerCommand.UpdateStep6Commercial toUpdateStep6Commercial() {
        return new PartnerCommand.UpdateStep6Commercial(feeSchedules, fxConfig, limits, contract);
    }
}
