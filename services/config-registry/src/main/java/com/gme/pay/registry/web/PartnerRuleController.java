package com.gme.pay.registry.web;

import com.gme.pay.contracts.PartnerCommand;
import com.gme.pay.contracts.RuleView;
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

import com.gme.pay.registry.rule.RuleService;

/**
 * Slice 6 — pricing-rule endpoints on the partner resource (wizard step 6's
 * rule editor). Kept in its own controller so each slice's surface stays
 * reviewable in isolation (the Slice 5 surface lives in
 * {@code PartnerPrefundingController}); all mount under {@code /v1/partners}
 * and share the partner-code-on-the-URL-line contract.
 *
 * <p>The legacy {@code RuleController} ({@code POST /v1/rules/validate})
 * stays untouched — it validates a free-standing lib-domain Rule payload;
 * this controller persists per-partner rule rows (V017).
 *
 * <p>{@code {partnerCode}} / {@code {id}} is always the human-facing business
 * code (e.g. {@code "GMEREMIT"}), never the BIGINT surrogate — same URL
 * contract as every other partner endpoint.
 */
@RestController
@RequestMapping("/v1/partners")
public class PartnerRuleController {

    private final RuleService ruleService;

    public PartnerRuleController(RuleService ruleService) {
        this.ruleService = ruleService;
    }

    /**
     * Save the Step-6 rule set onto an existing draft — bulk replace
     * ({@link RuleService#replaceDraftRules}): every current
     * {@code partner_rule} row is superseded and the new set inserted in one
     * transaction (SCD-6, ADR-010) with one {@code partner_rule} audit row
     * (ADR-007).
     *
     * <p>Returns 200 with the fresh current set as {@link RuleView}s; 404
     * unknown draft; 409 when the partner has left ONBOARDING; 400 on
     * validation failure (bad direction roster / negative or over-scale
     * margins / duplicate (scheme, direction) keys / lib-domain margin
     * invariant — cross-border {@code mA + mB >= 2%}, same-currency zero
     * margin) with the offending {@code rules[i]} index in the message.
     */
    @PatchMapping("/draft/{partnerCode}/step-6-rules")
    public List<RuleView> patchDraftStep6Rules(
            @PathVariable String partnerCode,
            @RequestBody PartnerCommand.UpdateStep6Rules req,
            @RequestHeader(value = "X-Actor", required = false) String actor) {
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body required");
        }
        return ruleService.replaceDraftRules(partnerCode, req.rules(), actor);
    }

    /**
     * The CURRENT rule set for {@code id} (the partner business code) — powers
     * the wizard's step-6 rehydrate and the partner detail page's pricing
     * tile. A partner with no rules yields an empty list; only an unknown
     * code 404s.
     */
    @GetMapping("/{id}/rules")
    public List<RuleView> listRules(@PathVariable String id) {
        return ruleService.currentRules(id);
    }
}
