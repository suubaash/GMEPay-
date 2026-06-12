package com.gme.pay.registry.web;

import com.gme.pay.contracts.PartnerCommand;
import com.gme.pay.contracts.PartnerCorridorView;
import com.gme.pay.registry.corridor.PartnerCorridorService;
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

/**
 * Slice 7 — corridor endpoints on the partner resource (wizard step 7's
 * corridor matrix builder). Kept in its own controller so each slice's
 * surface stays reviewable in isolation (the Slice 6 surface lives in
 * {@code PartnerRuleController} / {@code PartnerCommercialTermsController}).
 *
 * <p>Each operation is mounted on TWO paths:
 * <ul>
 *   <li>{@code /v1/admin/partners/...} — the Slice 7 ticket's canonical
 *       admin surface (the path the step-7 lane consumes end-to-end).</li>
 *   <li>{@code /v1/partners/...} — the registry-internal convention every
 *       prior slice uses ({@code PartnerRuleController} et al.), kept so the
 *       BFF's {@code RestConfigRegistryClient} pass-through wiring stays
 *       uniform with steps 1..6.</li>
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
public class PartnerCorridorController {

    private final PartnerCorridorService corridorService;

    public PartnerCorridorController(PartnerCorridorService corridorService) {
        this.corridorService = corridorService;
    }

    /**
     * Save the Step-7 corridor set onto an existing draft — bulk replace
     * ({@link PartnerCorridorService#replaceDraftCorridors}): every current
     * {@code partner_corridor} row is superseded and the new set inserted in
     * one transaction (SCD-6, ADR-010) with one {@code partner_corridor}
     * audit row (ADR-007).
     *
     * <p>Returns 200 with the fresh current set as
     * {@link PartnerCorridorView}s; 404 unknown draft; 409 when the partner
     * has left ONBOARDING; 400 on validation failure (bad ISO country /
     * currency codes, duplicate corridor lanes) with the offending
     * {@code corridors[i]} index in the message.
     */
    @PatchMapping({
            "/admin/partners/draft/{partnerCode}/step-7/corridors",
            "/partners/draft/{partnerCode}/step-7/corridors"})
    public List<PartnerCorridorView> patchDraftStep7Corridors(
            @PathVariable String partnerCode,
            @RequestBody PartnerCommand.UpdateStep7Corridors req,
            @RequestHeader(value = "X-Actor", required = false) String actor) {
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body required");
        }
        return corridorService.replaceDraftCorridors(partnerCode, req.corridors(), actor);
    }

    /**
     * The CURRENT corridor set for {@code partnerCode} — powers the wizard's
     * step-7 rehydrate, the partner detail page's corridor tile, and the
     * SchemeRouter / gateway corridor-gate reads. A partner with no corridors
     * yields an empty list; only an unknown code 404s.
     */
    @GetMapping({
            "/admin/partners/{partnerCode}/corridors",
            "/partners/{partnerCode}/corridors"})
    public List<PartnerCorridorView> listCorridors(@PathVariable String partnerCode) {
        return corridorService.currentCorridors(partnerCode);
    }
}
