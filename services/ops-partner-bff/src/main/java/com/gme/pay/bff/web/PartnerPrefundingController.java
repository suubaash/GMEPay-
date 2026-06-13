package com.gme.pay.bff.web;

import com.gme.pay.bff.client.ConfigRegistryClient;
import com.gme.pay.bff.web.dto.DraftPartnerStep5Request;
import com.gme.pay.contracts.PrefundingConfigView;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Slice 5 (5A.1) prefunding-config pass-throughs for the Admin UI wizard's
 * step-5 panel and the partner detail page's prefunding tile. Kept in its own
 * controller (rather than growing {@code PartnerSettlementController}) so each
 * slice surface stays reviewable in isolation; mounts under the same
 * {@code /v1/admin} prefix.
 *
 * <p>Pure pass-throughs: each call delegates to {@link ConfigRegistryClient},
 * which adapts to config-registry's
 * {@code /v1/partners/draft/{code}/step-5} and
 * {@code /v1/partners/{code}/prefunding-config} endpoints. Upstream
 * 400/404/409 pass through with their messages preserved. Money rides as
 * decimal STRINGS per {@code docs/MONEY_CONVENTION.md}.
 */
@RestController
@RequestMapping("/v1/admin")
public class PartnerPrefundingController {

    private final ConfigRegistryClient configRegistry;

    public PartnerPrefundingController(ConfigRegistryClient configRegistry) {
        this.configRegistry = configRegistry;
    }

    /**
     * Save the step-5 prefunding panel onto a draft — full-state replace of
     * the prefunding parameters. Mirrors
     * {@code PATCH /v1/partners/draft/{partnerCode}/step-5} on
     * config-registry: the current {@code partner_prefunding_config} row is
     * superseded and a fresh one inserted in one transaction (SCD-6, ADR-010)
     * with one audit row (ADR-007). Returns 200 with the fresh
     * {@link PrefundingConfigView}.
     */
    @PatchMapping("/partners/draft/{partnerCode}/step-5")
    public PrefundingConfigView patchDraftStep5(
            @PathVariable String partnerCode,
            @RequestBody DraftPartnerStep5Request body) {
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body required");
        }
        return configRegistry.patchDraftStep5(partnerCode, body.toUpdateStep5());
    }

    /**
     * The CURRENT prefunding config for {@code partnerCode}. Mirrors
     * {@code GET /v1/partners/{partnerCode}/prefunding-config}. 404 when the
     * code is unknown or no config exists yet (the wizard treats both as
     * "nothing to rehydrate").
     */
    @GetMapping("/partners/{partnerCode}/prefunding-config")
    public PrefundingConfigView getPrefundingConfig(@PathVariable String partnerCode) {
        return configRegistry.getPrefundingConfig(partnerCode);
    }
}
