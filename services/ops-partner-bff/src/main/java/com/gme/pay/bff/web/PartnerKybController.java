package com.gme.pay.bff.web;

import com.gme.pay.bff.client.ConfigRegistryClient;
import com.gme.pay.bff.web.dto.DraftPartnerStep3Request;
import com.gme.pay.contracts.KybView;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Slice 3 (3B.1) KYB pass-throughs for the Admin UI wizard's step 3 and the
 * partner detail page's screening panel. Kept in its own controller (rather
 * than growing {@code AdminDashboardController}) so each slice's BFF surface
 * stays reviewable in isolation; mounts under the same {@code /v1/admin}
 * prefix.
 *
 * <p>Pure pass-throughs: each call delegates to {@link ConfigRegistryClient},
 * which adapts to config-registry's {@code /v1/partners/draft/{code}/step-3},
 * {@code /v1/partners/{code}/kyb} and {@code /v1/partners/{code}/kyb/screen}
 * endpoints (config-registry in turn reaches the kyb-adapter service for the
 * actual screening, ADR-009). Upstream 400/404/409/502 pass through with
 * their messages preserved.
 */
@RestController
@RequestMapping("/v1/admin")
public class PartnerKybController {

    private final ConfigRegistryClient configRegistry;

    public PartnerKybController(ConfigRegistryClient configRegistry) {
        this.configRegistry = configRegistry;
    }

    /**
     * Save Step-3 (KYB) onto a draft — full-state replace of the
     * operator-editable fields. Mirrors
     * {@code PATCH /v1/partners/draft/{partnerCode}/step-3} on
     * config-registry: the current {@code partner_kyb} row is superseded and
     * a fresh one inserted in one transaction (SCD-6, ADR-010) with one
     * {@code partner_kyb} audit row (ADR-007); the screening verdict is
     * carried forward server-side. Returns 200 with the fresh {@link KybView}.
     */
    @PatchMapping("/partners/draft/{partnerCode}/step-3")
    public KybView patchDraftStep3(@PathVariable String partnerCode,
                                   @RequestBody DraftPartnerStep3Request body) {
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body required");
        }
        return configRegistry.patchDraftStep3(partnerCode, body.toUpdateStep3());
    }

    /**
     * The CURRENT KYB view for {@code partnerCode}. Mirrors
     * {@code GET /v1/partners/{partnerCode}/kyb}. 404 when the code is
     * unknown or no KYB row exists yet (the wizard treats both as "nothing to
     * rehydrate").
     */
    @GetMapping("/partners/{partnerCode}/kyb")
    public KybView getKyb(@PathVariable String partnerCode) {
        return configRegistry.getKyb(partnerCode);
    }

    /**
     * Run sanctions screening for {@code partnerCode} (the wizard's "Run
     * screening" button / the detail page's rescreen button). Mirrors
     * {@code POST /v1/partners/{partnerCode}/kyb/screen} — no body; the
     * server assembles the screening subject from the stored aggregate
     * (ADR-009). Returns 200 with the updated {@link KybView} carrying the
     * verdict; 404 unknown partner; upstream 502 when config-registry cannot
     * reach kyb-adapter.
     */
    @PostMapping("/partners/{partnerCode}/kyb/screen")
    public KybView runScreening(@PathVariable String partnerCode) {
        return configRegistry.runKybScreening(partnerCode);
    }
}
