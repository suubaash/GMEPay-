package com.gme.pay.bff.web;

import com.gme.pay.bff.client.ConfigRegistryClient;
import com.gme.pay.bff.web.dto.DraftPartnerStep4SettlementRequest;
import com.gme.pay.contracts.SettlementConfigView;
import com.gme.pay.contracts.SettlementPreview;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Slice 4 (4B.1) settlement-config pass-throughs for the Admin UI wizard's
 * step-4 settlement panel and the payout-date preview. Kept in its own
 * controller (rather than growing {@code PartnerBankAccountsController}) so
 * each slice surface stays reviewable in isolation; mounts under the same
 * {@code /v1/admin} prefix.
 *
 * <p>Pure pass-throughs: each call delegates to {@link ConfigRegistryClient},
 * which adapts to config-registry's
 * {@code /v1/partners/draft/{code}/step-4-settlement},
 * {@code /v1/partners/{code}/settlement-config} and
 * {@code /v1/partners/{code}/settlement-preview} endpoints. Upstream
 * 400/404/409 pass through with their messages preserved.
 */
@RestController
@RequestMapping("/v1/admin")
public class PartnerSettlementController {

    private final ConfigRegistryClient configRegistry;

    public PartnerSettlementController(ConfigRegistryClient configRegistry) {
        this.configRegistry = configRegistry;
    }

    /**
     * Save the step-4 settlement panel onto a draft — full-state replace of
     * the settlement parameters. Mirrors
     * {@code PATCH /v1/partners/draft/{partnerCode}/step-4-settlement} on
     * config-registry: the current {@code partner_settlement_config} row is
     * superseded and a fresh one inserted in one transaction (SCD-6, ADR-010)
     * with one audit row (ADR-007). Returns 200 with the fresh
     * {@link SettlementConfigView}.
     */
    @PatchMapping("/partners/draft/{partnerCode}/step-4-settlement")
    public SettlementConfigView patchDraftStep4Settlement(
            @PathVariable String partnerCode,
            @RequestBody DraftPartnerStep4SettlementRequest body) {
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body required");
        }
        return configRegistry.patchDraftStep4Settlement(
                partnerCode, body.toUpdateStep4Settlement());
    }

    /**
     * The CURRENT settlement config for {@code partnerCode}. Mirrors
     * {@code GET /v1/partners/{partnerCode}/settlement-config}. 404 when the
     * code is unknown or no config exists yet (the wizard treats both as
     * "nothing to rehydrate").
     */
    @GetMapping("/partners/{partnerCode}/settlement-config")
    public SettlementConfigView getSettlementConfig(@PathVariable String partnerCode) {
        return configRegistry.getSettlementConfig(partnerCode);
    }

    /**
     * Project a transaction instant onto a payout date (the wizard's "with
     * these settings, your Mon 11:30 KST txn pays out Wed" panel). Mirrors
     * {@code GET /v1/partners/{partnerCode}/settlement-preview?txnInstant=ISO}
     * — {@code txnInstant} passes through verbatim so config-registry owns the
     * parse and its 400 message; optional {@code bankCountry} (ISO-3166
     * alpha-2) overrides the partner's bank country for the holiday union.
     */
    @GetMapping("/partners/{partnerCode}/settlement-preview")
    public SettlementPreview getSettlementPreview(
            @PathVariable String partnerCode,
            @RequestParam("txnInstant") String txnInstant,
            @RequestParam(value = "bankCountry", required = false) String bankCountry) {
        return configRegistry.getSettlementPreview(partnerCode, txnInstant, bankCountry);
    }
}
