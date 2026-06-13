package com.gme.pay.registry.commercial;

import static com.gme.pay.registry.commercial.CommercialValidation.badRequest;

import com.gme.pay.contracts.CommercialTermsView;
import com.gme.pay.contracts.ContractView;
import com.gme.pay.contracts.FeeScheduleView;
import com.gme.pay.contracts.FxConfigView;
import com.gme.pay.contracts.LimitsView;
import com.gme.pay.contracts.PartnerCommand;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Slice 6 — the composite facade behind
 * {@code PATCH /v1/partners/draft/{code}/step-6-commercial}: applies the four
 * commercial sub-resources (fees + FX + limits + contract) of one
 * {@link PartnerCommand.UpdateStep6Commercial} payload ATOMICALLY.
 *
 * <h2>Atomicity</h2>
 *
 * <p>{@code @Transactional} on {@link #upsertStep6Commercial} makes this the
 * single transaction boundary; the four sub-services' own
 * {@code @Transactional} methods JOIN it (REQUIRED propagation), so a
 * validation failure in any later section rolls back every earlier section's
 * paired writes AND their audit rows — the wizard's step-6 save either lands
 * whole or not at all. (The fail-fast ordering inside each sub-service means
 * most failures reject before any row is touched anyway; the transaction is
 * the backstop for cross-section failures.)
 *
 * <h2>Section semantics</h2>
 *
 * <p>Non-null section = full-state replace of that sub-resource (for
 * {@code feeSchedules}, bulk replace — empty list clears). Null section =
 * UNTOUCHED, so the wizard can save a partially-completed panel. All-null is
 * a 400: there is nothing to save. Each applied section produces its own
 * audit row under its own {@code aggregateType} (ADR-007) — the composite is
 * an HTTP convenience, not an audit aggregate.
 */
@Service
public class CommercialTermsService {

    private final FeeScheduleService feeScheduleService;
    private final FxConfigService fxConfigService;
    private final LimitsService limitsService;
    private final ContractService contractService;

    public CommercialTermsService(FeeScheduleService feeScheduleService,
                                  FxConfigService fxConfigService,
                                  LimitsService limitsService,
                                  ContractService contractService) {
        this.feeScheduleService = feeScheduleService;
        this.fxConfigService = fxConfigService;
        this.limitsService = limitsService;
        this.contractService = contractService;
    }

    /**
     * Apply the step-6 commercial composite (see class javadoc). Sections the
     * caller did not include come back {@code null} in the returned
     * {@link CommercialTermsView} (they were not touched).
     *
     * @throws ResponseStatusException 400 when the body is missing or carries
     *         no sections; otherwise whatever the failing sub-service threw
     *         (404 unknown partner, 409 non-ONBOARDING, 400 validation) — the
     *         transaction rolls back all sections on any failure.
     */
    @Transactional
    public CommercialTermsView upsertStep6Commercial(String partnerCode,
                                                     PartnerCommand.UpdateStep6Commercial cmd,
                                                     String actor) {
        if (cmd == null) {
            throw badRequest("request body required");
        }
        if (cmd.feeSchedules() == null && cmd.fxConfig() == null
                && cmd.limits() == null && cmd.contract() == null) {
            throw badRequest("at least one of feeSchedules, fxConfig, limits, contract"
                    + " must be present (null sections are left untouched)");
        }

        List<FeeScheduleView> fees = cmd.feeSchedules() == null
                ? null
                : feeScheduleService.replaceDraftFeeSchedules(
                        partnerCode, cmd.feeSchedules(), actor);
        FxConfigView fx = cmd.fxConfig() == null
                ? null
                : fxConfigService.upsertFxConfig(partnerCode, cmd.fxConfig(), actor);
        LimitsView limits = cmd.limits() == null
                ? null
                : limitsService.upsertLimits(partnerCode, cmd.limits(), actor);
        ContractView contract = cmd.contract() == null
                ? null
                : contractService.upsertContract(partnerCode, cmd.contract(), actor);

        return new CommercialTermsView(fees, fx, limits, contract);
    }
}
