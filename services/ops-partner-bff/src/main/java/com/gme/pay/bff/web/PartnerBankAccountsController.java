package com.gme.pay.bff.web;

import com.gme.pay.bff.client.ConfigRegistryClient;
import com.gme.pay.bff.web.dto.DraftPartnerStep4Request;
import com.gme.pay.contracts.BankAccountView;
import java.util.List;
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
 * Slice 4 (4A.1) bank-account pass-throughs for the Admin UI wizard's step 4
 * and the partner detail page's banking panel. Kept in its own controller
 * (rather than growing {@code AdminDashboardController}) so each slice's BFF
 * surface stays reviewable in isolation — same split as
 * {@code PartnerKybController}; mounts under the same {@code /v1/admin}
 * prefix.
 *
 * <p>Pure pass-throughs: each call delegates to {@link ConfigRegistryClient},
 * which adapts to config-registry's
 * {@code /v1/partners/draft/{code}/step-4},
 * {@code /v1/partners/{code}/bank-accounts} and
 * {@code /v1/partners/{code}/bank-accounts/{id}/verify} endpoints. Upstream
 * 400/404/409/502 pass through with their messages preserved.
 */
@RestController
@RequestMapping("/v1/admin")
public class PartnerBankAccountsController {

    private final ConfigRegistryClient configRegistry;

    public PartnerBankAccountsController(ConfigRegistryClient configRegistry) {
        this.configRegistry = configRegistry;
    }

    /**
     * Save Step-4 (Banking &amp; Settlement) onto a draft — bulk replace.
     * Mirrors {@code PATCH /v1/partners/draft/{partnerCode}/step-4} on
     * config-registry: the body carries the FULL desired bank-account set;
     * upstream supersedes every current {@code partner_bank_account} row and
     * inserts the new set in one transaction (SCD-6, ADR-010) with one
     * {@code partner_bank_account} audit row (ADR-007), carrying verification
     * verdicts forward where the (currency, account number) pair is unchanged.
     * Returns 200 with the freshly-inserted current set; upstream 400/404/409
     * pass through with their messages preserved.
     */
    @PatchMapping("/partners/draft/{partnerCode}/step-4")
    public List<BankAccountView> patchDraftStep4(@PathVariable String partnerCode,
                                                 @RequestBody DraftPartnerStep4Request body) {
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body required");
        }
        return configRegistry.patchDraftStep4(partnerCode, body.toUpdateStep4());
    }

    /**
     * The CURRENT bank-account set for {@code partnerCode}. Mirrors
     * {@code GET /v1/partners/{partnerCode}/bank-accounts}. A partner with
     * zero accounts returns an empty list; an unknown code surfaces upstream's
     * 404.
     */
    @GetMapping("/partners/{partnerCode}/bank-accounts")
    public List<BankAccountView> listBankAccounts(@PathVariable String partnerCode) {
        return configRegistry.listBankAccounts(partnerCode);
    }

    /**
     * Run account verification for one CURRENT bank-account row (the step-4
     * "Verify" button). Mirrors
     * {@code POST /v1/partners/{partnerCode}/bank-accounts/{accountId}/verify}
     * — no body; config-registry assembles the subject from the stored row and
     * runs its {@code AccountVerificationProvider} port (KFTC for KR rails in
     * production, the deterministic stub by default). Returns 200 with the
     * FRESH SCD-6 row carrying the verdict (note: a new row id); 404 unknown
     * partner / non-current account id; upstream 502 when the verification
     * rail is unavailable.
     */
    @PostMapping("/partners/{partnerCode}/bank-accounts/{accountId}/verify")
    public BankAccountView verifyBankAccount(@PathVariable String partnerCode,
                                             @PathVariable Long accountId) {
        return configRegistry.verifyBankAccount(partnerCode, accountId);
    }
}
