package com.gme.pay.registry.web;

import com.gme.pay.contracts.PartnerCommand;
import com.gme.pay.contracts.SettlementConfigView;
import com.gme.pay.contracts.SettlementPreview;
import com.gme.pay.registry.settlement.SettlementConfigService;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Slice 4 — settlement-config endpoints on the partner resource (wizard step 4
 * settlement panel + the payout-date preview). Kept in its own controller so
 * each slice's surface stays reviewable in isolation (the bank-account half of
 * step 4 lives in {@code PartnerBankAccountController}); both mount under
 * {@code /v1/partners} and share the partner-code-on-the-URL-line contract.
 *
 * <p>{@code {partnerCode}} / {@code {id}} is always the human-facing business
 * code (e.g. {@code "GMEREMIT"}), never the BIGINT surrogate — same URL
 * contract as every other partner endpoint.
 */
@RestController
@RequestMapping("/v1/partners")
public class PartnerSettlementController {

    private final SettlementConfigService settlementService;

    public PartnerSettlementController(SettlementConfigService settlementService) {
        this.settlementService = settlementService;
    }

    /**
     * Save the Step-4 settlement panel onto an existing draft — full-state
     * replace of the settlement parameters
     * ({@link SettlementConfigService#upsertStep4Settlement}): the current
     * {@code partner_settlement_config} row is superseded and a fresh one
     * inserted in one transaction (SCD-6, ADR-010) with one audit row
     * (ADR-007).
     *
     * <p>Returns 200 with the fresh {@link SettlementConfigView}; 404 unknown
     * draft; 409 when the partner has left ONBOARDING; 400 on validation
     * failure (bad method roster / cycle range / timezone).
     */
    @PatchMapping("/draft/{partnerCode}/step-4-settlement")
    public SettlementConfigView patchDraftStep4Settlement(
            @PathVariable String partnerCode,
            @RequestBody PartnerCommand.UpdateStep4Settlement req,
            @RequestHeader(value = "X-Actor", required = false) String actor) {
        return settlementService.upsertStep4Settlement(partnerCode, req, actor);
    }

    /**
     * The CURRENT settlement config for {@code id} (the partner business code)
     * — powers the wizard's step-4 rehydrate and the partner detail page's
     * settlement panel. 404 when the code is unknown or no config exists yet.
     */
    @GetMapping("/{id}/settlement-config")
    public SettlementConfigView getSettlementConfig(@PathVariable String id) {
        return settlementService.currentConfig(id);
    }

    /**
     * Project a transaction instant onto a payout date through the partner's
     * CURRENT settlement config and the merged KR + bank-country holiday
     * calendars (V014) — the wizard's "with these settings, your Mon 11:30 KST
     * txn pays out Wed" preview.
     *
     * @param txnInstant  ISO-8601 instant (e.g. {@code 2026-09-23T08:30:00Z});
     *                    parsed here so a malformed value 400s with a readable
     *                    message instead of a binder error.
     * @param bankCountry optional ISO-3166 alpha-2 override of the partner's
     *                    bank country (defaults to the incorporation country
     *                    until the bank-account aggregate exposes a primary
     *                    PAYOUT account).
     */
    @GetMapping("/{id}/settlement-preview")
    public SettlementPreview getSettlementPreview(
            @PathVariable String id,
            @RequestParam("txnInstant") String txnInstant,
            @RequestParam(value = "bankCountry", required = false) String bankCountry) {
        Instant instant;
        try {
            instant = Instant.parse(txnInstant);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "txnInstant must be an ISO-8601 instant"
                            + " (e.g. 2026-09-23T08:30:00Z), was: " + txnInstant);
        }
        return settlementService.preview(id, instant, bankCountry);
    }
}
