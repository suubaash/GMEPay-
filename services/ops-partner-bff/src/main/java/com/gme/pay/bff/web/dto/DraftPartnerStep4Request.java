package com.gme.pay.bff.web.dto;

import com.gme.pay.contracts.BankAccountCommand;
import com.gme.pay.contracts.PartnerCommand;

import java.util.List;

/**
 * BFF wire shape for {@code PATCH /v1/admin/partners/draft/{partnerCode}/step-4}
 * (Slice 4 — Banking &amp; Settlement). The URL identifies the partner being
 * mutated; the body carries the FULL desired bank-account set (bulk-replace
 * semantics — an empty list clears all accounts, see
 * {@link PartnerCommand.UpdateStep4}).
 *
 * <p>Mirrors {@link PartnerCommand.UpdateStep4}; adapter {@link #toUpdateStep4()}
 * converts to the canonical write payload before the BFF calls config-registry —
 * the same seam discipline as {@link DraftPartnerStep2Request}. Elements bind
 * directly to the canonical {@link BankAccountCommand} (currency, bankName,
 * bicSwift, ibanOrAccountNumber, accountHolderName, bankCountry,
 * intermediaryBic, verificationEvidenceDocId, primary, swiftChargeBearer,
 * purpose). Verification status/date are NOT accepted on this payload — they
 * are provider-stamped via the verify endpoint.
 */
public record DraftPartnerStep4Request(List<BankAccountCommand> bankAccounts) {

    /** Adapt to the canonical write surface {@code lib-api-contracts} exposes. */
    public PartnerCommand.UpdateStep4 toUpdateStep4() {
        return new PartnerCommand.UpdateStep4(bankAccounts);
    }
}
