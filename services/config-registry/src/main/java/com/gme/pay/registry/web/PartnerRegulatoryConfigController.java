package com.gme.pay.registry.web;

import com.gme.pay.contracts.PartnerCommand;
import com.gme.pay.contracts.PartnerRegulatoryConfigView;
import com.gme.pay.registry.regulatory.PartnerRegulatoryConfigService;
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
 * Slice 8 Lane C — regulatory-attribute endpoints on the partner resource
 * (wizard step 8's regulatory panel: BOK 외환거래보고 + Hometax e-tax-invoice +
 * KoFIU CTR/STR + PIPA cross-border + Travel Rule). Kept in its own
 * controller so each slice's surface stays reviewable in isolation (the
 * Slice 7 surface lives in {@code PartnerCorridorController} /
 * {@code PartnerSchemeController}).
 *
 * <p>Each operation is mounted on TWO paths — the same dual-mount contract as
 * {@code PartnerCorridorController}:
 * <ul>
 *   <li>{@code /v1/admin/partners/...} — the Slice 8 ticket's canonical
 *       admin surface.</li>
 *   <li>{@code /v1/partners/...} — the registry-internal convention every
 *       prior slice uses, kept so the BFF's {@code RestConfigRegistryClient}
 *       pass-through wiring stays uniform with steps 1..7.</li>
 * </ul>
 * Both routes bind to the same service methods — one implementation, one
 * audit trail, two spellings of the URL.
 *
 * <p>{@code {partnerCode}} is always the human-facing business code (e.g.
 * {@code "GMEREMIT"}), never the BIGINT surrogate — same URL contract as
 * every other partner endpoint.
 */
@RestController
@RequestMapping("/v1")
public class PartnerRegulatoryConfigController {

    private final PartnerRegulatoryConfigService regulatoryService;

    public PartnerRegulatoryConfigController(PartnerRegulatoryConfigService regulatoryService) {
        this.regulatoryService = regulatoryService;
    }

    /**
     * Save the step-8 regulatory panel onto an existing draft — full-state
     * replace ({@link PartnerRegulatoryConfigService#upsertStep8}): the
     * current {@code partner_regulatory_config} row (if any) is superseded
     * and a fresh row inserted in one transaction (SCD-6, ADR-010) with one
     * {@code partner_regulatory_config} audit row (ADR-007).
     *
     * <p>Returns 200 with the fresh current row as a
     * {@link PartnerRegulatoryConfigView}; 404 unknown draft; 409 when the
     * partner has left ONBOARDING; 400 on validation failure (bad BOK code
     * shape, unknown roster value, non-ISO PIPA jurisdiction, non-positive
     * threshold, missing Travel-Rule endpoint) with the offending field named
     * in the message.
     */
    @PatchMapping({
            "/admin/partners/draft/{partnerCode}/step-8/regulatory",
            "/partners/draft/{partnerCode}/step-8/regulatory"})
    public PartnerRegulatoryConfigView patchDraftStep8Regulatory(
            @PathVariable String partnerCode,
            @RequestBody PartnerCommand.UpdateStep8Regulatory req,
            @RequestHeader(value = "X-Actor", required = false) String actor) {
        if (req == null || req.regulatory() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "request body with a 'regulatory' object required");
        }
        return regulatoryService.upsertStep8(partnerCode, req.regulatory(), actor);
    }

    /**
     * The CURRENT regulatory config for {@code partnerCode} — powers the
     * wizard's step-8 rehydrate, the partner detail page's regulatory tile,
     * and the reporting-compliance reads. 404 when the partner is unknown OR
     * when no step-8 save has happened yet (the message disambiguates) —
     * Lane A's activation gate uses
     * {@code PartnerRegulatoryConfigRepository.existsCurrentByPartnerId}, not
     * this endpoint.
     */
    @GetMapping({
            "/admin/partners/{partnerCode}/regulatory",
            "/partners/{partnerCode}/regulatory"})
    public PartnerRegulatoryConfigView getRegulatory(@PathVariable String partnerCode) {
        return regulatoryService.currentConfig(partnerCode);
    }
}
