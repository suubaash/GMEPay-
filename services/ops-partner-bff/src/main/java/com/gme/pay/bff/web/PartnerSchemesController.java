package com.gme.pay.bff.web;

import com.gme.pay.bff.client.ConfigRegistryClient;
import com.gme.pay.bff.web.dto.DraftPartnerStep7CorridorsRequest;
import com.gme.pay.bff.web.dto.DraftPartnerStep7SchemesRequest;
import com.gme.pay.contracts.PartnerCorridorView;
import com.gme.pay.contracts.PartnerSchemeView;
import com.gme.pay.contracts.SchemeOperatingHoursView;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Slice 7 (7A/7B) scheme-enablement and corridor pass-throughs for the Admin
 * UI wizard's step-7 panel (scheme enablement + corridor matrix) and the
 * partner detail page's scheme/corridor tiles. Kept in its own controller so
 * each slice surface stays reviewable in isolation; mounts under the same
 * {@code /v1/admin} prefix as the other wizard-step controllers.
 *
 * <p>Pure pass-throughs: each call delegates to {@link ConfigRegistryClient},
 * which adapts to config-registry's
 * {@code /v1/partners/draft/{code}/step-7/schemes},
 * {@code /v1/partners/draft/{code}/step-7/corridors},
 * {@code /v1/partners/{code}/schemes}, {@code /v1/partners/{code}/corridors},
 * and {@code /v1/schemes/{schemeId}/operating-hours} endpoints. Upstream
 * 400/404/409 pass through with their messages preserved.
 */
@RestController
@RequestMapping("/v1/admin")
public class PartnerSchemesController {

    private final ConfigRegistryClient configRegistry;

    public PartnerSchemesController(ConfigRegistryClient configRegistry) {
        this.configRegistry = configRegistry;
    }

    /**
     * Save the step-7 scheme enablement set onto a draft. Mirrors
     * {@code PATCH /v1/partners/draft/{partnerCode}/step-7/schemes} on
     * config-registry: bulk-replace semantics — the incoming list supersedes
     * every current {@code partner_scheme} row in one transaction (SCD-6 paired
     * writes, ADR-010; one audit row per element, ADR-007). An empty list clears
     * all schemes; {@code null} schemes array is 400. Returns 200 with the fresh
     * {@link PartnerSchemeView} list.
     */
    @PatchMapping("/partners/draft/{partnerCode}/step-7/schemes")
    public List<PartnerSchemeView> patchDraftStep7Schemes(
            @PathVariable String partnerCode,
            @RequestBody DraftPartnerStep7SchemesRequest body) {
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body required");
        }
        return configRegistry.patchDraftStep7Schemes(
                partnerCode, body.toUpdateStep7Schemes());
    }

    /**
     * Save the step-7 corridor matrix onto a draft. Mirrors
     * {@code PATCH /v1/partners/draft/{partnerCode}/step-7/corridors} on
     * config-registry: bulk-replace semantics — the incoming list supersedes
     * every current {@code partner_corridor} row in one transaction (SCD-6 paired
     * writes, ADR-010; one audit row per element, ADR-007). An empty list clears
     * all corridors; {@code null} corridors array is 400. Returns 200 with the
     * fresh {@link PartnerCorridorView} list.
     */
    @PatchMapping("/partners/draft/{partnerCode}/step-7/corridors")
    public List<PartnerCorridorView> patchDraftStep7Corridors(
            @PathVariable String partnerCode,
            @RequestBody DraftPartnerStep7CorridorsRequest body) {
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body required");
        }
        return configRegistry.patchDraftStep7Corridors(
                partnerCode, body.toUpdateStep7Corridors());
    }

    /**
     * The CURRENT scheme-enablement set for {@code partnerCode}. Mirrors
     * {@code GET /v1/partners/{partnerCode}/schemes}. Empty list when the
     * partner has no scheme rows yet; 404 only for an unknown code.
     */
    @GetMapping("/partners/{partnerCode}/schemes")
    public List<PartnerSchemeView> getSchemes(@PathVariable String partnerCode) {
        return configRegistry.listSchemeEnablements(partnerCode);
    }

    /**
     * The CURRENT corridor set for {@code partnerCode}. Mirrors
     * {@code GET /v1/partners/{partnerCode}/corridors}. Empty list when the
     * partner has no corridor rows yet; 404 only for an unknown code.
     */
    @GetMapping("/partners/{partnerCode}/corridors")
    public List<PartnerCorridorView> getCorridors(@PathVariable String partnerCode) {
        return configRegistry.listCorridors(partnerCode);
    }

    /**
     * The operating-hours schedule for a scheme. Mirrors
     * {@code GET /v1/schemes/{schemeId}/operating-hours}. Empty list when no
     * schedule has been seeded yet; 404 for an unknown {@code schemeId}.
     */
    @GetMapping("/schemes/{schemeId}/operating-hours")
    public List<SchemeOperatingHoursView> getOperatingHours(@PathVariable String schemeId) {
        return configRegistry.getSchemeOperatingHours(schemeId);
    }
}
