package com.gme.pay.registry.bank.verify;

import com.gme.pay.registry.bank.BankVerificationStatus;
import java.time.Instant;

/**
 * Slice 4 port for bank-account verification — the seam between
 * {@code PartnerBankAccountService} and whichever verification rail applies
 * (same hexagonal-port discipline as the ADR-009 {@code KybScreeningClient}
 * seam to kyb-adapter).
 *
 * <p>Adapter roster:
 * <ul>
 *   <li>{@link StubVerificationAdapter} — deterministic default (active when
 *       {@code gmepay.account-verification.provider} is unset or {@code stub});
 *       returns KFTC_VERIFIED for KR accounts and BANK_LETTER otherwise so
 *       local dev / unit slices run with zero external infrastructure.</li>
 *   <li>{@link KftcVerificationAdapter} — KFTC 계좌실명조회 (account-holder
 *       real-name check) placeholder, activated by
 *       {@code gmepay.account-verification.provider=kftc}; throws until the
 *       production certificate is provisioned.</li>
 * </ul>
 *
 * <p>Implementations must be side-effect free with respect to the registry's
 * database: the SERVICE owns the SCD-6 write that records the verdict; the
 * provider only answers "is this account real and held by this name".
 */
public interface AccountVerificationProvider {

    /**
     * Verify one bank account. Implementations decide the applicable rail from
     * {@link AccountRef#bankCountry()} (KFTC for KR, letter/micro-deposit
     * otherwise).
     *
     * @param accountRef the account coordinates as stored on the CURRENT row —
     *                   callers cannot verify a subject that differs from the
     *                   aggregate (same assembly rule as KYB screening).
     * @return the provider's verdict; never {@code null}.
     */
    VerificationResult verify(AccountRef accountRef);

    /**
     * The account coordinates handed to the provider — a projection of the
     * CURRENT {@code partner_bank_account} row, assembled by the service.
     */
    record AccountRef(
            String partnerCode,
            String bankCountry,
            String currency,
            String ibanOrAccountNumber,
            String accountHolderName) {
    }

    /**
     * One verification verdict.
     *
     * @param status      the verdict the service stamps onto the fresh SCD-6
     *                    row (never {@code null}; a failed check surfaces as an
     *                    exception from {@link #verify}, not as a status).
     * @param evidenceRef provider-side reference of the check (KFTC inquiry id,
     *                    letter hash, ...). Recorded in the service log — the
     *                    durable evidence DOCUMENT (e.g. the bank letter scan)
     *                    is linked separately via
     *                    {@code verification_evidence_doc_id}.
     * @param verifiedAt  when the provider performed the check; the service
     *                    truncates to MICROS / derives the stored
     *                    {@code verification_date} (UTC) from it.
     */
    record VerificationResult(
            BankVerificationStatus status,
            String evidenceRef,
            Instant verifiedAt) {
    }
}
