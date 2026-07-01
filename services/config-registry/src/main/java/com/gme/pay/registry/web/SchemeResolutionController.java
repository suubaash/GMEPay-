package com.gme.pay.registry.web;

import com.gme.pay.contracts.PartnerSchemeView;
import com.gme.pay.registry.scheme.PartnerSchemeService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Wave-3 cross-partner scheme location-resolution surface (smart-router
 * consumes this). Kept OUT of {@link PartnerSchemeController} — that controller
 * is mounted under {@code /v1/admin} for the partner-scoped wizard step-7
 * editor, whereas this is a service-to-service read keyed by location, not by
 * one partner. It lives under the flat {@code /v1/schemes} namespace alongside
 * the catalog picker.
 */
@RestController
@RequestMapping("/v1/schemes")
public class SchemeResolutionController {

    private final PartnerSchemeService schemeService;

    public SchemeResolutionController(PartnerSchemeService schemeService) {
        this.schemeService = schemeService;
    }

    /**
     * Every CURRENT scheme enablement as a {@link PartnerSchemeView}, each
     * carrying the owning partner's operating country plus the derived
     * {@code supportsCpm}/{@code supportsMpm}/{@code status} fields, optionally
     * narrowed to one ISO-3166 alpha-2 {@code country}.
     *
     * <p>An absent/blank {@code country} returns the full set; an unknown
     * country simply returns an empty list (no 404 — "no scheme enabled there"
     * is a valid resolver answer, not an error).
     */
    @GetMapping("/resolve")
    public List<PartnerSchemeView> resolveByLocation(
            @RequestParam(value = "country", required = false) String country) {
        return schemeService.resolveByLocation(country);
    }
}
