package com.gme.pay.registry.web;

import com.gme.pay.contracts.EffectiveCommissionView;
import com.gme.pay.registry.commercial.CommissionResolutionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /v1/commission/effective} — resolves the effective two-sided
 * commission split for a concrete (scheme × partner × direction), applying the
 * documented exact-over-wildcard precedence (V031). The single source of truth
 * that revenue-ledger / payment-executor / settlement bind to instead of
 * re-implementing the precedence.
 *
 * <p>Lenient: an unknown partner or an unconfigured side returns a {@code null}
 * share on that side rather than a 404; {@code resolved} is true only when both
 * sides resolved.
 */
@RestController
public class CommissionResolutionController {

    private final CommissionResolutionService service;

    public CommissionResolutionController(CommissionResolutionService service) {
        this.service = service;
    }

    /**
     * @param schemeId    scheme code (e.g. {@code ZEROPAY}); required.
     * @param partnerCode partner business code (e.g. {@code GMEREMIT}); required.
     * @param direction   {@code INBOUND}/{@code OUTBOUND}/{@code BOTH}; optional
     *                    (omitted matches only the wildcard rows on each side).
     */
    @GetMapping("/v1/commission/effective")
    public EffectiveCommissionView effective(
            @RequestParam String schemeId,
            @RequestParam String partnerCode,
            @RequestParam(required = false) String direction) {
        return service.resolve(schemeId, partnerCode, direction);
    }
}
