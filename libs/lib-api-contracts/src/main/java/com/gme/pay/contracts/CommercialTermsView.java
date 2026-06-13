package com.gme.pay.contracts;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Composite read DTO returned by the step-6 commercial save
 * ({@code PATCH /v1/partners/draft/{code}/step-6-commercial}, Slice 6 — see
 * {@code docs/PARTNER_SETUP_PLAN.md} §"Slice 6 — Commercial Terms"): the
 * fresh state of each sub-resource the composite
 * {@link PartnerCommand.UpdateStep6Commercial} payload carried.
 *
 * <p>Sections the caller did NOT include in the save come back {@code null}
 * (they were not touched — the wizard rehydrates untouched sections via the
 * per-sub-resource GETs: {@code /fee-schedules}, {@code /fx-config},
 * {@code /limits}, {@code /contract}).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CommercialTermsView(
        List<FeeScheduleView> feeSchedules,
        FxConfigView fxConfig,
        LimitsView limits,
        ContractView contract) {
}
