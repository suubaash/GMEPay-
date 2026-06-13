package com.gme.pay.bff.web;

import com.gme.pay.bff.client.ConfigRegistryClient;
import com.gme.pay.bff.web.dto.DraftPartnerStep8RegulatoryRequest;
import com.gme.pay.contracts.PartnerRegulatoryConfigView;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Slice 8 Lane C — regulatory-attribute pass-throughs for the Admin UI wizard's
 * step-8 regulatory panel (BOK 외환거래보고 + Hometax e-tax-invoice + KoFIU
 * CTR/STR + PIPA cross-border + Travel Rule) and the partner detail page's
 * regulatory tile.
 *
 * <p>Pure pass-throughs: each call delegates to {@link ConfigRegistryClient},
 * which adapts to config-registry's
 * {@code PATCH /v1/admin/partners/draft/{code}/step-8/regulatory} and
 * {@code GET /v1/admin/partners/{code}/regulatory} endpoints.
 * Upstream 400/404/409 pass through with their messages preserved.
 */
@RestController
@RequestMapping("/v1/admin")
public class PartnerRegulatoryController {

    private final ConfigRegistryClient configRegistry;

    public PartnerRegulatoryController(ConfigRegistryClient configRegistry) {
        this.configRegistry = configRegistry;
    }

    /**
     * Save the step-8 regulatory panel onto an existing draft — full-state
     * replace (SCD-6, ADR-010). Returns 200 with the fresh
     * {@link PartnerRegulatoryConfigView}; 404 unknown draft; 409 when the
     * partner has left ONBOARDING; 400 on validation failure.
     */
    @PatchMapping("/partners/draft/{partnerCode}/step-8/regulatory")
    public PartnerRegulatoryConfigView patchDraftStep8Regulatory(
            @PathVariable String partnerCode,
            @RequestBody DraftPartnerStep8RegulatoryRequest body,
            @RequestHeader(value = "X-Actor", required = false) String actor) {
        if (body == null || body.regulatory() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "request body with a 'regulatory' object required");
        }
        return configRegistry.patchDraftStep8Regulatory(
                partnerCode, body.toUpdateStep8Regulatory(), actor);
    }

    /**
     * The CURRENT regulatory config for {@code partnerCode}. 404 when the
     * partner is unknown or no step-8 regulatory save has happened yet.
     */
    @GetMapping("/partners/{partnerCode}/regulatory")
    public PartnerRegulatoryConfigView getRegulatory(@PathVariable String partnerCode) {
        return configRegistry.getRegulatory(partnerCode);
    }
}
