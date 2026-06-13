package com.gme.pay.registry.web;

import com.gme.pay.contracts.BankAccountView;
import com.gme.pay.contracts.PartnerCommand;
import com.gme.pay.registry.bank.PartnerBankAccountService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Slice 4 — Banking &amp; Settlement endpoints on the partner resource (wizard
 * step 4 + account verification). Kept in its own controller (rather than
 * growing {@code PartnerController}) so each slice's surface stays reviewable
 * in isolation — the same split as {@code PartnerKybController}; both mount
 * under {@code /v1/partners} and share the partner-code-on-the-URL-line
 * contract ({@code {partnerCode}} / {@code {id}} is always the human-facing
 * business code, never the BIGINT surrogate).
 */
@RestController
@RequestMapping("/v1/partners")
public class PartnerBankAccountController {

    private final PartnerBankAccountService bankAccountService;

    public PartnerBankAccountController(PartnerBankAccountService bankAccountService) {
        this.bankAccountService = bankAccountService;
    }

    /**
     * Save Step-4 (Banking &amp; Settlement) onto an existing draft — bulk
     * replace. The body carries the FULL desired bank-account set and
     * {@link PartnerBankAccountService#replaceDraftBankAccounts} supersedes
     * every current {@code partner_bank_account} row + inserts the new set in
     * one transaction (SCD-6 paired writes, ADR-010), publishing one
     * {@code partner_bank_account} audit row (ADR-007). Verification verdicts
     * carry forward when the (currency, account number) pair is unchanged.
     *
     * <p>Returns 200 with the freshly-inserted current set; 404 if no current
     * row matches {@code partnerCode}; 409 if the partner has left
     * {@code ONBOARDING} (post-activation bank changes wait for the Slice 8
     * 2-authorized-signatory flow); 400 on validation failure (BIC shape,
     * IBAN mod-97, one-primary-per-currency — message carries the offending
     * {@code bankAccounts[i]} index).
     */
    @PatchMapping("/draft/{partnerCode}/step-4")
    public List<BankAccountView> patchDraftStep4(
            @PathVariable String partnerCode,
            @RequestBody PartnerCommand.UpdateStep4 req,
            @RequestHeader(value = "X-Actor", required = false) String actor) {
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body required");
        }
        return bankAccountService.replaceDraftBankAccounts(partnerCode, req.bankAccounts(), actor);
    }

    /**
     * The CURRENT bank-account set for {@code id} (the partner business code).
     * Powers the wizard's step-4 rehydrate and the partner detail page.
     * Returns an empty list for a partner with no accounts yet; 404 only when
     * the partner code itself is unknown.
     */
    @GetMapping("/{id}/bank-accounts")
    public List<BankAccountView> bankAccounts(@PathVariable String id) {
        return bankAccountService.currentBankAccounts(id);
    }

    /**
     * Run account verification for one CURRENT bank-account row via the
     * {@code AccountVerificationProvider} port (KFTC for KR rails in
     * production, the deterministic stub by default) and record the verdict
     * (status + verification date) on a fresh SCD-6 row. No request body —
     * the server assembles the verification subject from the stored row.
     * Not gated on ONBOARDING (re-verifications are evidence, mirroring KYB
     * rescreens).
     *
     * <p>Returns 200 with the fresh row (NOTE: a new row id — SCD-6 mints a
     * fresh row per write); 404 when the partner code is unknown or
     * {@code accountId} is not a current row of that partner; 502 when the
     * verification rail is unavailable (e.g. the KFTC certificate-pending
     * placeholder).
     */
    @PostMapping("/{id}/bank-accounts/{accountId}/verify")
    public BankAccountView verify(@PathVariable String id,
                                  @PathVariable Long accountId,
                                  @RequestHeader(value = "X-Actor", required = false) String actor) {
        return bankAccountService.verifyBankAccount(id, accountId, actor);
    }
}
