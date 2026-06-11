package com.gme.pay.contracts;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Canonical read DTO for a partner's KYB sub-resource ({@code partner_kyb},
 * Slice 3 — see {@code docs/PARTNER_SETUP_PLAN.md} §"Slice 3 — KYB"). The JSON
 * shape every consumer of config-registry's KYB endpoints binds to, mirroring
 * how {@link PartnerView} / {@link ContactView} are the single read shapes for
 * their aggregates.
 *
 * <ul>
 *   <li>{@code id} — BIGSERIAL surrogate of the KYB ROW: under SCD-6 every
 *       step-3 save / screening run mints a fresh row, so the id changes
 *       across saves (audit reference, not a stable identifier).</li>
 *   <li>{@code riskRating} — {@code LOW} | {@code MEDIUM} | {@code HIGH}
 *       (operator-assigned per FATF R.10 risk-based approach); String per the
 *       {@code legalForm} precedent, config-registry validates the roster.</li>
 *   <li>{@code riskRationale} — why that rating (&le; 1000 chars; the
 *       regulator reads this).</li>
 *   <li>{@code nextReviewDate} — when periodic CDD review falls due.</li>
 *   <li>{@code licenseType} / {@code licenseNumber} / {@code licenseAuthority}
 *       / {@code licenseExpiry} — the partner's remittance/PSP license.</li>
 *   <li>{@code uboList} — declared ultimate beneficial owners.</li>
 *   <li>{@code cbddqDocId} — FK to the Wolfsberg CBDDQ document in
 *       {@code partner_document} (the vault row, ADR-006); {@code null} until
 *       uploaded.</li>
 *   <li>{@code screeningStatus} / {@code screeningProviderRef} /
 *       {@code screenedAt} — latest sanctions screening verdict stored on the
 *       row ({@code CLEAR} | {@code HIT} | {@code NEEDS_REVIEW}, the vendor
 *       run reference, and when it completed). {@code null} before the first
 *       run.</li>
 *   <li>Bitemporal stamps (ADR-010): {@code validFrom} / {@code validTo}
 *       (business time) and {@code recordedAt} (transaction time of this row
 *       version).</li>
 * </ul>
 *
 * <p>{@code @JsonInclude(ALWAYS)} so {@code null} fields stay on the wire —
 * the UI relies on field presence, same contract as {@link PartnerView}.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record KybView(
        Long id,
        String riskRating,
        String riskRationale,
        LocalDate nextReviewDate,
        String licenseType,
        String licenseNumber,
        String licenseAuthority,
        LocalDate licenseExpiry,
        List<UboView> uboList,
        Long cbddqDocId,
        String screeningStatus,
        String screeningProviderRef,
        Instant screenedAt,
        Instant validFrom,
        Instant validTo,
        Instant recordedAt) {
}
