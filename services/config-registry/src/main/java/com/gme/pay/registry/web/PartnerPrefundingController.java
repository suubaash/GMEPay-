package com.gme.pay.registry.web;

import com.gme.pay.contracts.PartnerCommand;
import com.gme.pay.contracts.PrefundingConfigView;
import com.gme.pay.registry.prefunding.PrefundingConfigService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Slice 5 — prefunding-config endpoints on the partner resource (wizard step
 * 5). Kept in its own controller so each slice's surface stays reviewable in
 * isolation (the Slice 4 surfaces live in {@code PartnerBankAccountController}
 * / {@code PartnerSettlementController}); all mount under
 * {@code /v1/partners} and share the partner-code-on-the-URL-line contract.
 *
 * <p>{@code {partnerCode}} / {@code {id}} is always the human-facing business
 * code (e.g. {@code "GMEREMIT"}), never the BIGINT surrogate — same URL
 * contract as every other partner endpoint.
 */
@RestController
@RequestMapping("/v1/partners")
public class PartnerPrefundingController {

    private final PrefundingConfigService prefundingService;

    public PartnerPrefundingController(PrefundingConfigService prefundingService) {
        this.prefundingService = prefundingService;
    }

    /**
     * Save the Step-5 prefunding panel onto an existing draft — full-state
     * replace of the prefunding parameters
     * ({@link PrefundingConfigService#upsertStep5}): the current
     * {@code partner_prefunding_config} row is superseded and a fresh one
     * inserted in one transaction (SCD-6, ADR-010) with one audit row
     * (ADR-007).
     *
     * <p>Returns 200 with the fresh {@link PrefundingConfigView}; 404 unknown
     * draft; 409 when the partner has left ONBOARDING; 400 on validation
     * failure (bad funding model / non-positive threshold / pattern without
     * {@code {partner_code}} / top-up account that is not a current
     * FLOAT_TOPUP row of this partner).
     */
    @PatchMapping("/draft/{partnerCode}/step-5")
    public PrefundingConfigView patchDraftStep5(
            @PathVariable String partnerCode,
            @RequestBody PartnerCommand.UpdateStep5 req,
            @RequestHeader(value = "X-Actor", required = false) String actor) {
        return prefundingService.upsertStep5(partnerCode, req, actor);
    }

    /**
     * The CURRENT prefunding config for {@code id} (the partner business code)
     * — powers the wizard's step-5 rehydrate and the partner detail page's
     * prefunding tile. 404 when the code is unknown or no config exists yet.
     */
    @GetMapping("/{id}/prefunding-config")
    public PrefundingConfigView getPrefundingConfig(@PathVariable String id) {
        return prefundingService.currentConfig(id);
    }
}
