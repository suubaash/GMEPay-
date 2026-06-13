package com.gme.pay.contracts;

/**
 * Write shape for one partner bank account — the JSON object the UI / BFF sends
 * as an element of the step-4 bulk-replace payload
 * ({@link PartnerCommand.UpdateStep4}). Fields mirror {@link BankAccountView};
 * see that record for the field-level contract.
 *
 * <p>Discrimination fields ({@code swiftChargeBearer}, {@code purpose}) are
 * carried as Strings (not enums) following the {@code legalForm} /
 * {@code ContactCommand.role} precedent — config-registry validates them
 * against its rosters (V012 CHECK constraints) and rejects unknown values
 * with 400.
 *
 * <p>Server-side validation (config-registry {@code PartnerBankAccountService}):
 * <ul>
 *   <li>{@code currency} — required, ISO-4217 shape (3 uppercase letters).</li>
 *   <li>{@code bankName} — required, non-blank, &le; 140 chars.</li>
 *   <li>{@code bicSwift} / {@code intermediaryBic} — optional; when present
 *       must match {@code ^[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?$} (BIC-8/BIC-11).</li>
 *   <li>{@code ibanOrAccountNumber} — required, &le; 34 chars. When the value
 *       starts with two letters + two digits it is treated as an IBAN and the
 *       ISO 13616 mod-97 checksum is enforced; otherwise it is a raw domestic
 *       account number (KR accounts are not IBAN) and only shape-checked.</li>
 *   <li>{@code accountHolderName} — required, non-blank, &le; 140 chars.</li>
 *   <li>{@code bankCountry} — required, ISO-3166 alpha-2 shape (2 uppercase
 *       letters).</li>
 *   <li>{@code verificationEvidenceDocId} — optional; references a
 *       {@code partner_document} row (the DocumentVault upload of e.g. a bank
 *       letter scan). Must be positive when present.</li>
 *   <li>{@code primary} — optional; {@code null} is treated as {@code false}.
 *       At most ONE primary account per currency across the payload.</li>
 *   <li>{@code swiftChargeBearer} — optional; one of OUR / BEN / SHA.</li>
 *   <li>{@code purpose} — optional; one of PAYOUT / FLOAT_TOPUP / REFUND;
 *       {@code null} defaults to PAYOUT.</li>
 * </ul>
 *
 * <p>Verification status/date are intentionally NOT on this record — they are
 * provider-stamped via {@code POST .../bank-accounts/{id}/verify}, never
 * operator-typed. A bulk replace carries an existing verification forward when
 * the (currency, ibanOrAccountNumber) pair is unchanged.
 */
public record BankAccountCommand(
        String currency,
        String bankName,
        String bicSwift,
        String ibanOrAccountNumber,
        String accountHolderName,
        String bankCountry,
        String intermediaryBic,
        Long verificationEvidenceDocId,
        Boolean primary,
        String swiftChargeBearer,
        String purpose) {
}
