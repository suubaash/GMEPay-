package com.gme.pay.contracts;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Canonical read DTO for one partner bank account (Slice 4 — see
 * {@code docs/PARTNER_SETUP_PLAN.md} §"Slice 4 — Banking &amp; Settlement").
 * The JSON shape every consumer of config-registry's bank-account endpoints
 * binds to, mirroring {@link ContactView} for the contact aggregate.
 *
 * <ul>
 *   <li>{@code id} — BIGSERIAL surrogate of the bank-account ROW (not the
 *       account itself): under SCD-6 every bulk replace / verification mints a
 *       fresh row, so the id changes across saves. Useful as a React key, an
 *       audit reference and the verify-endpoint path id — not as a stable
 *       account identifier.</li>
 *   <li>{@code currency} — ISO-4217 settlement currency of the account.</li>
 *   <li>{@code bankName}, {@code bicSwift}, {@code ibanOrAccountNumber},
 *       {@code accountHolderName}, {@code bankCountry},
 *       {@code intermediaryBic} — the account coordinates. IBAN values carry a
 *       validated mod-97 checksum; non-IBAN values (e.g. KR domestic account
 *       numbers) are stored as typed.</li>
 *   <li>{@code verificationStatus} — UNVERIFIED / KFTC_VERIFIED / BANK_LETTER /
 *       MICRO_DEPOSIT. Stamped by the verification provider, never
 *       operator-typed.</li>
 *   <li>{@code verificationEvidenceDocId} — {@code partner_document} row id of
 *       the uploaded evidence (e.g. bank letter scan) or {@code null}.</li>
 *   <li>{@code verificationDate} — date the verification verdict landed, or
 *       {@code null} while UNVERIFIED.</li>
 *   <li>{@code primary} — TRUE for the payout account of record in its
 *       currency (at most one per currency, service-enforced).</li>
 *   <li>{@code swiftChargeBearer} — OUR / BEN / SHA, or {@code null} for
 *       non-SWIFT rails.</li>
 *   <li>{@code purpose} — PAYOUT / FLOAT_TOPUP / REFUND.</li>
 *   <li>Bitemporal stamps (ADR-010): {@code validFrom} / {@code validTo}
 *       (business time, half-open) and {@code recordedAt} (transaction time of
 *       this row version).</li>
 * </ul>
 *
 * <p>{@code @JsonInclude(ALWAYS)} so {@code null} fields stay on the wire —
 * the UI relies on field presence, same contract as {@link ContactView}.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record BankAccountView(
        Long id,
        String currency,
        String bankName,
        String bicSwift,
        String ibanOrAccountNumber,
        String accountHolderName,
        String bankCountry,
        String intermediaryBic,
        String verificationStatus,
        Long verificationEvidenceDocId,
        LocalDate verificationDate,
        boolean primary,
        String swiftChargeBearer,
        String purpose,
        Instant validFrom,
        Instant validTo,
        Instant recordedAt) {
}
