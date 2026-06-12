package com.gme.pay.registry.web;

import com.gme.pay.contracts.CommercialTermsView;
import com.gme.pay.contracts.ContractView;
import com.gme.pay.contracts.FeeScheduleView;
import com.gme.pay.contracts.FxConfigView;
import com.gme.pay.contracts.LimitsView;
import com.gme.pay.contracts.PartnerCommand;
import com.gme.pay.registry.commercial.CommercialTermsService;
import com.gme.pay.registry.commercial.ContractService;
import com.gme.pay.registry.commercial.FeeScheduleService;
import com.gme.pay.registry.commercial.FxConfigService;
import com.gme.pay.registry.commercial.LimitsService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Slice 6 — commercial-terms endpoints on the partner resource (wizard step
 * 6: fees + FX + limits + contract). Kept in its own controller so each
 * slice's surface stays reviewable in isolation (the Slice 4/5 surfaces live
 * in {@code PartnerBankAccountController} / {@code PartnerSettlementController}
 * / {@code PartnerPrefundingController}); all mount under
 * {@code /v1/partners} and share the partner-code-on-the-URL-line contract.
 *
 * <p>{@code {partnerCode}} / {@code {id}} is always the human-facing business
 * code (e.g. {@code "GMEREMIT"}), never the BIGINT surrogate — same URL
 * contract as every other partner endpoint.
 */
@RestController
@RequestMapping("/v1/partners")
public class PartnerCommercialTermsController {

    private final CommercialTermsService commercialTermsService;
    private final FeeScheduleService feeScheduleService;
    private final FxConfigService fxConfigService;
    private final LimitsService limitsService;
    private final ContractService contractService;

    public PartnerCommercialTermsController(CommercialTermsService commercialTermsService,
                                            FeeScheduleService feeScheduleService,
                                            FxConfigService fxConfigService,
                                            LimitsService limitsService,
                                            ContractService contractService) {
        this.commercialTermsService = commercialTermsService;
        this.feeScheduleService = feeScheduleService;
        this.fxConfigService = fxConfigService;
        this.limitsService = limitsService;
        this.contractService = contractService;
    }

    /**
     * Save the Step-6 commercial panel onto an existing draft — the composite
     * payload carries up to four sections (fees / FX / limits / contract),
     * applied ATOMICALLY by
     * {@link CommercialTermsService#upsertStep6Commercial}: each non-null
     * section is a full-state (or, for fees, bulk) SCD-6 replace with its own
     * audit row; null sections are left untouched; a failure in any section
     * rolls back all of them.
     *
     * <p>Returns 200 with the fresh {@link CommercialTermsView} (untouched
     * sections come back null); 404 unknown draft; 409 when the partner has
     * left ONBOARDING; 400 on validation failure — including the
     * server-enforced 소액해외송금업 caps (perTxnMax &le; 5,000 USD, annualCap
     * &le; 50,000 USD for {@code licenseType=SOAEK_HAEOEMONG}) — or when all
     * four sections are null.
     */
    @PatchMapping("/draft/{partnerCode}/step-6-commercial")
    public CommercialTermsView patchDraftStep6Commercial(
            @PathVariable String partnerCode,
            @RequestBody PartnerCommand.UpdateStep6Commercial req,
            @RequestHeader(value = "X-Actor", required = false) String actor) {
        return commercialTermsService.upsertStep6Commercial(partnerCode, req, actor);
    }

    /**
     * The CURRENT fee-schedule set for {@code id} (the partner business code)
     * — powers the wizard's step-6 rehydrate and the partner detail page.
     * Returns an empty list for a partner with no fee rows yet; 404 only when
     * the partner code itself is unknown.
     */
    @GetMapping("/{id}/fee-schedules")
    public List<FeeScheduleView> getFeeSchedules(@PathVariable String id) {
        return feeScheduleService.currentFeeSchedules(id);
    }

    /**
     * The CURRENT FX config for {@code id}. 404 when the code is unknown or
     * no FX config exists yet.
     */
    @GetMapping("/{id}/fx-config")
    public FxConfigView getFxConfig(@PathVariable String id) {
        return fxConfigService.currentFxConfig(id);
    }

    /**
     * The CURRENT limits for {@code id}. 404 when the code is unknown or no
     * limits row exists yet.
     */
    @GetMapping("/{id}/limits")
    public LimitsView getLimits(@PathVariable String id) {
        return limitsService.currentLimits(id);
    }

    /**
     * The CURRENT contract for {@code id}. 404 when the code is unknown or no
     * contract row exists yet.
     */
    @GetMapping("/{id}/contract")
    public ContractView getContract(@PathVariable String id) {
        return contractService.currentContract(id);
    }
}
