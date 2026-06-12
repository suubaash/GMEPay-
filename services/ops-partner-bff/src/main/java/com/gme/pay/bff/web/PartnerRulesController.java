package com.gme.pay.bff.web;

import com.gme.pay.bff.client.ConfigRegistryClient;
import com.gme.pay.bff.web.dto.DraftPartnerStep6RulesRequest;
import com.gme.pay.contracts.RuleView;
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
 * Slice 6 (6A.1) pricing-rule pass-throughs for the Admin UI wizard's step-6
 * rule editor and the partner detail page's pricing tile. Kept in its own
 * controller (rather than growing {@code PartnerPrefundingController}) so each
 * slice's BFF surface stays reviewable in isolation; mounts under the same
 * {@code /v1/admin} prefix.
 *
 * <p>Pure pass-throughs: each call delegates to {@link ConfigRegistryClient},
 * which adapts to config-registry's
 * {@code /v1/partners/draft/{code}/step-6-rules} and
 * {@code /v1/partners/{code}/rules} endpoints. Upstream 400/404/409 pass
 * through with their messages preserved. Margins and money ride as decimal
 * STRINGS per {@code docs/MONEY_CONVENTION.md}.
 */
@RestController
@RequestMapping("/v1/admin")
public class PartnerRulesController {

    private final ConfigRegistryClient configRegistry;

    public PartnerRulesController(ConfigRegistryClient configRegistry) {
        this.configRegistry = configRegistry;
    }

    /**
     * Save the step-6 rule set onto a draft — bulk replace. Mirrors
     * {@code PATCH /v1/partners/draft/{partnerCode}/step-6-rules} on
     * config-registry: the body carries the FULL desired rule set; upstream
     * supersedes every current {@code partner_rule} row (V017) and inserts the
     * new set in one transaction (SCD-6, ADR-010) with one
     * {@code partner_rule} audit row (ADR-007), enforcing the lib-domain
     * margin invariant (cross-border {@code mA + mB >= 2%}, same-currency zero
     * margin) against the partner's V016 collection/settle currency split.
     * Returns 200 with the freshly-inserted current set; upstream 400/404/409
     * pass through with their messages preserved.
     */
    @PatchMapping("/partners/draft/{partnerCode}/step-6-rules")
    public List<RuleView> patchDraftStep6Rules(@PathVariable String partnerCode,
                                               @RequestBody DraftPartnerStep6RulesRequest body) {
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body required");
        }
        return configRegistry.patchDraftStep6Rules(partnerCode, body.toUpdateStep6Rules());
    }

    /**
     * The CURRENT pricing-rule set for {@code partnerCode}. Mirrors
     * {@code GET /v1/partners/{partnerCode}/rules}. A partner with zero rules
     * returns an empty list; an unknown code surfaces upstream's 404.
     */
    @GetMapping("/partners/{partnerCode}/rules")
    public List<RuleView> listRules(@PathVariable String partnerCode) {
        return configRegistry.listRules(partnerCode);
    }
}
