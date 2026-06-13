package com.gme.pay.registry.web;

import com.gme.pay.contracts.PartnerCommand;
import com.gme.pay.contracts.PartnerSchemeView;
import com.gme.pay.contracts.SchemeOperatingHoursView;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.gme.pay.registry.scheme.PartnerSchemeService;

/**
 * Slice 7 — scheme-enablement endpoints on the partner resource (wizard step
 * 7's scheme editor) plus the read-only operating-hours reference lookup.
 * Kept in its own controller so each slice's surface stays reviewable in
 * isolation (the Slice 6 surface lives in {@code PartnerRuleController} /
 * {@code PartnerCommercialTermsController}).
 *
 * <p>Mounted under {@code /v1/admin} per the Slice 7 endpoint contract — the
 * step-7 surface is admin-wizard-only (the BFF adds Keycloak OIDC role-gating
 * in front of it), unlike the flat {@code /v1/partners} namespace of the
 * earlier slices.
 *
 * <p>{@code {partnerCode}} is always the human-facing business code (e.g.
 * {@code "GMEREMIT"}), never the BIGINT surrogate — same URL contract as
 * every other partner endpoint.
 */
@RestController
@RequestMapping("/v1/admin")
public class PartnerSchemeController {

    private final PartnerSchemeService schemeService;

    public PartnerSchemeController(PartnerSchemeService schemeService) {
        this.schemeService = schemeService;
    }

    /**
     * Save the Step-7 scheme set onto an existing draft — bulk replace
     * ({@link PartnerSchemeService#replaceDraftSchemes}): every current
     * {@code partner_scheme} row is superseded and the new set inserted in one
     * transaction (SCD-6, ADR-010) with one {@code partner_scheme} audit row
     * (ADR-007).
     *
     * <p>Returns 200 with the fresh current set as {@link PartnerSchemeView}s;
     * 404 unknown draft; 409 when the partner has left ONBOARDING; 400 on
     * validation failure (scheme / direction / role / approval-method roster,
     * over-width fields, duplicate schemeId, or the ZEROPAY cross-field
     * invariant — an enabled ZEROPAY row needs {@code zeropayMerchantId} +
     * {@code kftcInstitutionCode}) with the offending {@code schemes[i]} index
     * in the message.
     */
    @PatchMapping("/partners/draft/{partnerCode}/step-7/schemes")
    public List<PartnerSchemeView> patchDraftStep7Schemes(
            @PathVariable String partnerCode,
            @RequestBody PartnerCommand.UpdateStep7Schemes req,
            @RequestHeader(value = "X-Actor", required = false) String actor) {
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body required");
        }
        return schemeService.replaceDraftSchemes(partnerCode, req.schemes(), actor);
    }

    /**
     * The CURRENT scheme set for {@code partnerCode} — powers the wizard's
     * step-7 rehydrate and the partner detail page's scheme tile. A partner
     * with no schemes yields an empty list; only an unknown code 404s.
     */
    @GetMapping("/partners/{partnerCode}/schemes")
    public List<PartnerSchemeView> listSchemes(@PathVariable String partnerCode) {
        return schemeService.currentSchemes(partnerCode);
    }

    /**
     * The weekly operating schedule for one scheme (V024 reference data) —
     * 7 rows, Monday(0) .. Sunday(6), each carrying the local open/close
     * window, the optional settlement cutoff and the IANA timezone they are
     * evaluated in. 404 when {@code schemeId} is not in the V022 roster; a
     * rostered scheme whose schedule is not yet seeded (QRIS / KHQR) returns
     * an empty list.
     */
    @GetMapping("/schemes/{schemeId}/operating-hours")
    public List<SchemeOperatingHoursView> operatingHours(@PathVariable String schemeId) {
        return schemeService.operatingHours(schemeId);
    }
}
