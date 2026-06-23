package com.gme.pay.bff.web;

import com.gme.pay.bff.client.ConfigRegistryClient;
import com.gme.pay.bff.web.dto.DraftPartnerStep6CommercialRequest;
import com.gme.pay.contracts.CommercialTermsView;
import com.gme.pay.contracts.ContractView;
import com.gme.pay.contracts.FeeScheduleView;
import com.gme.pay.contracts.FxConfigView;
import com.gme.pay.contracts.LimitsView;
import com.gme.pay.contracts.PartnerCommissionShareCommand;
import com.gme.pay.contracts.PartnerCommissionShareView;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Slice 6 (6B.1) commercial-terms pass-throughs for the Admin UI wizard's
 * step-6 panel (fees + FX + limits + contract) and the partner detail page's
 * commercial tiles. Kept in its own controller so each slice surface stays
 * reviewable in isolation; mounts under the same {@code /v1/admin} prefix as
 * the other wizard-step controllers.
 *
 * <p>Pure pass-throughs: each call delegates to {@link ConfigRegistryClient},
 * which adapts to config-registry's
 * {@code /v1/partners/draft/{code}/step-6-commercial} and per-sub-resource
 * GET endpoints. Upstream 400/404/409 pass through with their messages
 * preserved — including the server-enforced 소액해외송금업 caps. Money and
 * bps ride as decimal STRINGS per {@code docs/MONEY_CONVENTION.md}.
 */
@RestController
@RequestMapping("/v1/admin")
public class PartnerCommercialTermsController {

    private final ConfigRegistryClient configRegistry;

    public PartnerCommercialTermsController(ConfigRegistryClient configRegistry) {
        this.configRegistry = configRegistry;
    }

    /**
     * Save the step-6 commercial composite onto a draft. Mirrors
     * {@code PATCH /v1/partners/draft/{partnerCode}/step-6-commercial} on
     * config-registry: each non-null section is applied atomically (SCD-6
     * paired writes, ADR-010; one audit row per section, ADR-007), null
     * sections are left untouched. Returns 200 with the fresh
     * {@link CommercialTermsView} (untouched sections null).
     */
    @PatchMapping("/partners/draft/{partnerCode}/step-6-commercial")
    public CommercialTermsView patchDraftStep6Commercial(
            @PathVariable String partnerCode,
            @RequestBody DraftPartnerStep6CommercialRequest body) {
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body required");
        }
        return configRegistry.patchDraftStep6Commercial(
                partnerCode, body.toUpdateStep6Commercial());
    }

    /**
     * The CURRENT fee-schedule set for {@code partnerCode}. Mirrors
     * {@code GET /v1/partners/{partnerCode}/fee-schedules}. Empty list when
     * the partner has no fee rows yet; 404 only for an unknown code.
     */
    @GetMapping("/partners/{partnerCode}/fee-schedules")
    public List<FeeScheduleView> getFeeSchedules(@PathVariable String partnerCode) {
        return configRegistry.getFeeSchedules(partnerCode);
    }

    /**
     * The CURRENT FX config for {@code partnerCode}. Mirrors
     * {@code GET /v1/partners/{partnerCode}/fx-config}. 404 when the code is
     * unknown or no FX config exists yet.
     */
    @GetMapping("/partners/{partnerCode}/fx-config")
    public FxConfigView getFxConfig(@PathVariable String partnerCode) {
        return configRegistry.getFxConfig(partnerCode);
    }

    /**
     * The CURRENT limits for {@code partnerCode}. Mirrors
     * {@code GET /v1/partners/{partnerCode}/limits}. 404 when the code is
     * unknown or no limits row exists yet.
     */
    @GetMapping("/partners/{partnerCode}/limits")
    public LimitsView getLimits(@PathVariable String partnerCode) {
        return configRegistry.getLimits(partnerCode);
    }

    /**
     * The CURRENT contract for {@code partnerCode}. Mirrors
     * {@code GET /v1/partners/{partnerCode}/contract}. 404 when the code is
     * unknown or no contract row exists yet.
     */
    @GetMapping("/partners/{partnerCode}/contract")
    public ContractView getContract(@PathVariable String partnerCode) {
        return configRegistry.getContract(partnerCode);
    }

    /**
     * The CURRENT partner-side commission shares for {@code partnerCode} — the
     * configurable GME↔partner split of GME's commission (V031). Mirrors
     * {@code GET /v1/partners/{partnerCode}/commission-shares}. Empty list when
     * none configured; 404 only for an unknown code.
     */
    @GetMapping("/partners/{partnerCode}/commission-shares")
    public List<PartnerCommissionShareView> getCommissionShares(@PathVariable String partnerCode) {
        return configRegistry.listPartnerCommissionShares(partnerCode);
    }

    /**
     * Bulk-replace the partner-side commission shares (one row per
     * {@code (schemeId, direction)}; empty list clears). Mirrors
     * {@code PUT /v1/partners/{partnerCode}/commission-shares}; upstream 400
     * (validation) / 404 (unknown partner) pass through with their messages.
     */
    @PutMapping("/partners/{partnerCode}/commission-shares")
    public List<PartnerCommissionShareView> replaceCommissionShares(
            @PathVariable String partnerCode,
            @RequestBody List<PartnerCommissionShareCommand> shares) {
        if (shares == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body required");
        }
        return configRegistry.replacePartnerCommissionShares(partnerCode, shares);
    }
}
