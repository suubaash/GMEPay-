package com.gme.pay.contracts;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Canonical read DTO for one partner contact (Slice 2 — see
 * {@code docs/PARTNER_SETUP_PLAN.md} §"Slice 2 — Contacts"). The JSON shape every
 * consumer of config-registry's contact endpoints binds to, mirroring how
 * {@link PartnerView} is the single read shape for the partner aggregate.
 *
 * <ul>
 *   <li>{@code id} — BIGSERIAL surrogate of the contact ROW (not the contact
 *       "person"): under SCD-6 every bulk replace mints fresh rows, so the id
 *       changes across saves. Useful as a React key / audit reference, not as a
 *       stable person identifier.</li>
 *   <li>{@code role} — functional role: {@code OPS_24X7}, {@code FINANCE},
 *       {@code COMPLIANCE_MLRO}, {@code TECH}, {@code LEGAL}, {@code INCIDENT}.
 *       Carried as String per the {@code legalForm} precedent on
 *       {@link PartnerView}; config-registry validates the roster.</li>
 *   <li>{@code name}, {@code email}, {@code phoneE164} — the person. Phone is
 *       E.164 ({@code +} then up to 15 digits) or {@code null}.</li>
 *   <li>{@code authorizedSignatory} — TRUE when this person may approve
 *       bank-account changes (Slice 4's 2-signatory rule reads this).</li>
 *   <li>{@code notes} — free-text operator annotation (&le; 500 chars).</li>
 *   <li>Bitemporal stamps (ADR-010): {@code validFrom} / {@code validTo}
 *       (business time, half-open) and {@code recordedAt} (transaction time of
 *       this row version). The current set is whatever the GET endpoint returns;
 *       historical sets stay queryable server-side.</li>
 * </ul>
 *
 * <p>{@code @JsonInclude(ALWAYS)} so {@code null} fields stay on the wire — the
 * UI relies on field presence to distinguish "not populated" from "not
 * serialised", same contract as {@link PartnerView}.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ContactView(
        Long id,
        String role,
        String name,
        String email,
        String phoneE164,
        boolean authorizedSignatory,
        String notes,
        Instant validFrom,
        Instant validTo,
        Instant recordedAt) {
}
